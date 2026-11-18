package com.commercecore.diaz.platform.http.error;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(requiredProperties={"type","title","status","detail","instance","correlationId"})
public record ProblemDetails(
        @Schema(format="uri-reference") String type,
        String title,
        int status,
        String detail,
        @Schema(nullable=true,format="uri-reference") String instance,
        @Schema(format="uuid") String correlationId) { }
