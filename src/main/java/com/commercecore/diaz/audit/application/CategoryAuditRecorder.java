package com.commercecore.diaz.audit.application;
/** Records category changes in the caller's transaction. */
public interface CategoryAuditRecorder { void record(CategoryAuditEvent event); }
