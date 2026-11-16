package com.commercecore.diaz.tenant.application.port;

import java.util.Optional;

/** Authorization port for platform-only tenant creation. */
public interface TenantCreationAuthorization {
    record Actor(String issuer, String subject) { }
    boolean canCreate();
    Optional<Actor> actor();
}
