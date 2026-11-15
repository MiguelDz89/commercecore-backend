package com.commercecore.diaz.identity.application;

import java.util.UUID;

/** Provision only the local account of the current verified OIDC actor. */
public interface AccountProvisioning {
    UUID provisionCurrentActor();
}
