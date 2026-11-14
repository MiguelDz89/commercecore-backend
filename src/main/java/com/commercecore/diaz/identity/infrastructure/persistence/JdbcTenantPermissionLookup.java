package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.AuthorizationEvaluationException;
import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import com.commercecore.diaz.identity.domain.ActorId;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcTenantPermissionLookup implements TenantPermissionLookup {
    private static final String SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM user_account u
                JOIN tenant_membership m ON m.user_id = u.user_id
                JOIN membership_role mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.membership_id
                JOIN app_role r ON r.tenant_id = mr.tenant_id AND r.role_id = mr.role_id
                JOIN role_permission rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.role_id
                JOIN permission p ON p.permission_code = rp.permission_code
                WHERE u.oidc_issuer = ? AND u.oidc_subject = ? AND u.status = 'ACTIVE'
                  AND m.tenant_id = ? AND m.status = 'ACTIVE' AND p.permission_code = ?
            )
            """;
    private final DataSource dataSource;

    public JdbcTenantPermissionLookup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean hasPermission(ActorId actor, UUID tenantId, String permissionCode) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, actor.issuer());
            statement.setString(2, actor.subject());
            statement.setObject(3, tenantId);
            statement.setString(4, permissionCode);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Authorization query returned no result");
                }
                return result.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new AuthorizationEvaluationException("Unable to evaluate tenant authorization", e);
        }
    }
}
