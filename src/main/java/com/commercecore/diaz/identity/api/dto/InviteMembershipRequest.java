package com.commercecore.diaz.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(example="{\"accountId\":\"00000000-0000-4000-8000-000000000001\"}")
public record InviteMembershipRequest(@NotNull UUID accountId) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown request property"); }
}
