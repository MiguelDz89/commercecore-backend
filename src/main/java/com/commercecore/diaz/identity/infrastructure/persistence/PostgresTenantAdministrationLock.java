package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.TenantAdministrationLockException;
import com.commercecore.diaz.identity.application.TenantAdministrationLockException.Reason;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class PostgresTenantAdministrationLock implements TenantAdministrationLock {
    private final DataSource dataSource;

    public PostgresTenantAdministrationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void acquireAll(Collection<UUID> tenantIds) {
        Objects.requireNonNull(tenantIds, "tenantIds");
        var ordered = tenantIds.stream().map(id -> Objects.requireNonNull(id, "tenantId")).distinct().sorted().toList();
        if (ordered.isEmpty()) throw new IllegalArgumentException("At least one tenant is required");
        try (var connection = dataSource.getConnection()) {
            String previous = setting(connection);
            setTimeout(connection, "3s");
            try (var statement = connection.prepareStatement("""
                    SELECT pg_advisory_xact_lock(hashtextextended('commercecore:identity:tenant-admin:' || ?, 0))
                    """)) {
                for (UUID tenantId : ordered) {
                    statement.setString(1, tenantId.toString());
                    statement.execute();
                }
            }
            setTimeout(connection, previous);
        } catch (SQLException e) {
            Reason reason = switch (e.getSQLState() == null ? "" : e.getSQLState()) {
                case "55P03" -> Reason.TIMEOUT;
                case "40P01" -> Reason.DEADLOCK;
                default -> Reason.DATABASE_ERROR;
            };
            throw new TenantAdministrationLockException(reason, e);
        }
    }

    private static String setting(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT current_setting('lock_timeout')");
             var result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Cannot read lock timeout");
            return result.getString(1);
        }
    }

    private static void setTimeout(Connection connection, String timeout) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)")) {
            statement.setString(1, timeout);
            statement.execute();
        }
    }
}
