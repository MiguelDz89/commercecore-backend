package com.commercecore.diaz.catalog.application;
import java.util.UUID;
public record CategoryPatch(boolean namePresent,String name,boolean descriptionPresent,String description,
                            boolean parentPresent,UUID parentId) { }
