package com.commercecore.diaz.identity.application;
import java.util.Optional;
import java.util.UUID;
/** Resolves the authenticated actor's active local account without provisioning. */
public interface ActiveAccountQuery { Optional<UUID> currentAccountId(); }
