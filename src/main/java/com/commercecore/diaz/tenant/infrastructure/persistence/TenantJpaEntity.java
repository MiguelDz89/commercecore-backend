package com.commercecore.diaz.tenant.infrastructure.persistence;

import com.commercecore.diaz.tenant.domain.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "tenant")
public class TenantJpaEntity {
    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    UUID tenantId;
    @Column(nullable = false, length = 80, updatable = false)
    String slug;
    @Column(name = "legal_name", nullable = false, length = 200)
    String legalName;
    @Column(name = "display_name", nullable = false, length = 160)
    String displayName;
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "char(3)")
    String currencyCode;
    @Column(name = "time_zone", nullable = false, length = 64)
    String timeZone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    TenantStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected TenantJpaEntity() {
    }
}
