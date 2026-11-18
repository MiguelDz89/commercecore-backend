package com.commercecore.diaz.catalog.api.dto;

import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(requiredProperties = "correlationId")
public record ProductStatusChangeResponse(@Schema(format = "uuid") UUID correlationId) { }
