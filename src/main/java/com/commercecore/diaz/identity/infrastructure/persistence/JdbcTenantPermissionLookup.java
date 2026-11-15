package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.AuthorizationEvaluationException;
import com.commercecore.diaz.identity.application.AuthorizationIntegrityException;
import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import com.commercecore.diaz.identity.domain.ActorId;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcTenantPermissionLookup implements TenantPermissionLookup {
    private static final String SQL = """
            WITH actor_roles AS (
                SELECT r.tenant_id, r.role_id, r.code
                FROM user_account u
                JOIN tenant_membership m ON m.user_id = u.user_id
                JOIN membership_role mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.membership_id
                JOIN app_role r ON r.tenant_id = mr.tenant_id AND r.role_id = mr.role_id
                WHERE u.oidc_issuer = ? AND u.oidc_subject = ? AND u.status = 'ACTIVE'
                  AND m.tenant_id = ? AND m.status = 'ACTIVE'
            ), role_state AS (
                SELECT r.role_id, r.code, count(rp.permission_code) AS permission_count,
                       count(rp.permission_code) FILTER (WHERE rp.permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')) AS admin_count,
                       count(rp.permission_code) FILTER (WHERE rp.permission_code IN ('TENANT_MANAGE','USER_MANAGE')) AS forbidden_count,
                       coalesce(bool_or(rp.permission_code = ?), false) AS grants_permission
                FROM actor_roles r LEFT JOIN role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.role_id
                GROUP BY r.role_id, r.code
            )
            SELECT coalesce(bool_or(code ~ '(^[[:space:]]|[[:space:]]$)'
                    OR (upper(btrim(code))='TENANT_ADMIN' AND (code <> 'TENANT_ADMIN' OR permission_count <> 3 OR admin_count <> 3))
                    OR (upper(btrim(code)) <> 'TENANT_ADMIN' AND forbidden_count > 0)), false) AS invalid_role,
                   coalesce(bool_or(grants_permission), false) AS permitted
            FROM role_state
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
                if (result.getBoolean(1)) {
                    throw new AuthorizationIntegrityException("Inconsistent roles for authenticated actor and tenant");
                }
                return result.getBoolean(2);
            }
        } catch (SQLException e) {
            throw new AuthorizationEvaluationException("Unable to evaluate tenant authorization", e);
        }
    }
}
