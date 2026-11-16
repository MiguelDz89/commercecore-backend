package com.commercecore.diaz.platform.http.error;
public record ProblemDetails(String type, String title, int status, String detail, String instance, String correlationId) { }
