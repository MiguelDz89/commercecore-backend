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
                INSERT INTO audit_event (tenant_id, actor_user_id, entity_type, entity_id, action, before_data, after_data, correlation_id)
                VALUES (?, ?, ?, ?, ?, CASE WHEN CAST(? AS text[]) IS NULL THEN NULL
                    ELSE jsonb_build_object('permissions', CAST(? AS text[])) END,
                    CASE WHEN CAST(? AS text[]) IS NOT NULL THEN jsonb_build_object('permissions', CAST(? AS text[]))
                         WHEN CAST(? AS uuid) IS NOT NULL THEN jsonb_build_object('relatedEntityId', CAST(? AS uuid))
                         ELSE NULL END, ?)
                """)) {
            statement.setObject(1, event.tenantId());
            statement.setObject(2, event.actorUserId());
            statement.setString(3, event.entityType().name());
            statement.setObject(4, event.entityId());
            statement.setString(5, event.action().name());
            var before = event.previousPermissions() == null ? null : connection.createArrayOf("text", event.previousPermissions().stream().sorted().toArray());
            var after = event.newPermissions() == null ? null : connection.createArrayOf("text", event.newPermissions().stream().sorted().toArray());
            statement.setArray(6, before); statement.setArray(7, before);
            statement.setArray(8, after); statement.setArray(9, after);
            statement.setObject(10, event.relatedEntityId()); statement.setObject(11, event.relatedEntityId());
            statement.setString(12, event.correlationId() == null ? null : event.correlationId().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to record tenant audit event", e);
        }
    }
}
