package com.commercecore.diaz.catalog.application;
import java.util.UUID;
public record ProductPatch(long version,boolean namePresent,String name,boolean descriptionPresent,String description,
                           boolean categoryPresent,UUID categoryId) { }
