package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.port.LocalAccountStore;
import com.commercecore.diaz.identity.domain.ActorId;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcLocalAccountStore implements LocalAccountStore {
    private final DataSource dataSource;

    public JdbcLocalAccountStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public UUID findOrCreate(ActorId actor) {
        try (var connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO user_account (oidc_issuer, oidc_subject) VALUES (?, ?)
                    ON CONFLICT (oidc_issuer, oidc_subject) DO NOTHING RETURNING user_id
                    """)) {
                insert.setString(1, actor.issuer());
                insert.setString(2, actor.subject());
                try (var row = insert.executeQuery()) {
                    if (row.next()) return row.getObject(1, UUID.class);
                }
            }
            try (var select = connection.prepareStatement("""
                    SELECT user_id FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?
                    """)) {
                select.setString(1, actor.issuer());
                select.setString(2, actor.subject());
                try (var row = select.executeQuery()) {
                    if (row.next()) return row.getObject(1, UUID.class);
                }
            }
            throw new SQLException("Account not visible after conflict");
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to provision local account", e);
        }
    }
}
