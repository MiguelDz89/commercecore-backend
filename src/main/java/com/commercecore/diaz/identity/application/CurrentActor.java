package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.Optional;

public interface CurrentActor {
    Optional<ActorId> id();

    boolean hasPlatformSuperAdminRole();
}
