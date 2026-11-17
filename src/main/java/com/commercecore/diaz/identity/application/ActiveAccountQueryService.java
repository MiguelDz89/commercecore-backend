package com.commercecore.diaz.identity.application;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;
@ApplicationScoped
public class ActiveAccountQueryService implements ActiveAccountQuery {
    private final CurrentActor actor;
    private final MembershipStore accounts;
    public ActiveAccountQueryService(CurrentActor actor, MembershipStore accounts) { this.actor=actor; this.accounts=accounts; }
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public Optional<UUID> currentAccountId() { return actor.id().flatMap(accounts::activeAccount); }
}
