package com.commercecore.diaz.identity.application.port;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.UUID;

/** Internal port for idempotent persistence of a verified actor. */
public interface LocalAccountStore {
    UUID findOrCreate(ActorId actor);
}
