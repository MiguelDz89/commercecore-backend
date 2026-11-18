package com.commercecore.diaz.audit.infrastructure.persistence;

import com.commercecore.diaz.audit.application.ProductAuditEvent;
import com.commercecore.diaz.audit.application.ProductAuditRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcProductAuditRecorder implements ProductAuditRecorder {
    private static final String INSERT = """
            INSERT INTO audit_event(tenant_id, actor_user_id, entity_type, entity_id, action,
                                    before_data, after_data, correlation_id)
            VALUES (?,?,'PRODUCT',?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
            """;

    private final DataSource source;
    private final ObjectMapper mapper;

    public JdbcProductAuditRecorder(DataSource source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public void record(ProductAuditEvent event) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(INSERT)) {
            bind(statement, event.tenantId(), event.actorAccountId(), event.productId(), "PRODUCT_" + event.action().name(),
                    event.before(), event.after(), null);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Product audit was not inserted");
        } catch (SQLException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to record product audit", error);
        }
    }

    @Override
    public void recordStatusChanges(UUID tenant, UUID actor, UUID correlationId, String reason,
                                    List<ProductAuditEvent.StatusChange> changes) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(INSERT)) {
            for (var change : changes) {
                ObjectNode after = mapper.valueToTree(change.after());
                if (reason != null && !reason.isBlank()) after.put("reason", reason);
                bind(statement, tenant, actor, change.productId(), "PRODUCT_STATUS_CHANGED",
                        change.before(), after, correlationId);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            if (counts.length != changes.size()) throw new IllegalStateException("Incomplete product audit");
            for (int count : counts) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO)
                    throw new IllegalStateException("Product audit was not inserted");
            }
        } catch (SQLException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to record product status audit", error);
        }
    }

    private void bind(PreparedStatement statement, UUID tenant, UUID actor, UUID productId, String action,
                      Object before, Object after, UUID correlationId) throws SQLException, JsonProcessingException {
        statement.setObject(1, tenant);
        statement.setObject(2, actor);
        statement.setObject(3, productId);
        statement.setString(4, action);
        statement.setString(5, before == null ? null : mapper.writeValueAsString(before));
        statement.setString(6, mapper.writeValueAsString(after));
        statement.setString(7, correlationId == null ? null : correlationId.toString());
    }
}
