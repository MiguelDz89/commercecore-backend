package com.commercecore.diaz.audit.infrastructure.persistence;

import com.commercecore.diaz.audit.application.TenantAuditEvent;
import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcTenantAuditRecorder implements TenantAuditRecorder {
    private final DataSource dataSource;

    public JdbcTenantAuditRecorder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void record(TenantAuditEvent event) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO audit_event (tenant_id, actor_user_id, entity_type, entity_id, action, after_data, correlation_id)
                VALUES (?, ?, ?, ?, ?, CASE WHEN CAST(? AS uuid) IS NULL THEN NULL
                    ELSE jsonb_build_object('relatedEntityId', CAST(? AS uuid)) END, ?)
                """)) {
            statement.setObject(1, event.tenantId());
            statement.setObject(2, event.actorUserId());
            statement.setString(3, event.entityType().name());
            statement.setObject(4, event.entityId());
            statement.setString(5, event.action().name());
            statement.setObject(6, event.relatedEntityId());
            statement.setObject(7, event.relatedEntityId());
            statement.setString(8, event.correlationId() == null ? null : event.correlationId().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to record tenant audit event", e);
        }
    }
}
