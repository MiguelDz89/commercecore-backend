package com.commercecore.diaz.identity.domain;

import java.util.Objects;

public record ActorId(String issuer, String subject) {
    public ActorId {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("Issuer and subject must not be blank");
        }
    }
}
