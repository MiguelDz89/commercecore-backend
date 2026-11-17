package com.commercecore.diaz.catalog.application;
public class CategoryConflictException extends RuntimeException {
    public CategoryConflictException(String message) { super(message); }
    public CategoryConflictException(String message, Throwable cause) { super(message,cause); }
}
