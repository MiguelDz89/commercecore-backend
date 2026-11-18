package com.commercecore.diaz.catalog.application;
import java.util.UUID;
import java.util.Map;
public record ProductPatch(long version,boolean namePresent,String name,boolean descriptionPresent,String description,
                           boolean categoryPresent,UUID categoryId,String baseUnit,Boolean fractionalQuantitiesEnabled,
                           Map<String,String> attributes) { }
