package com.commercecore.diaz.catalog.domain;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
public record Product(UUID id,UUID tenantId,UUID categoryId,String sku,String name,String description,Status status,
                      long version,Instant createdAt,Instant updatedAt,String baseUnit,boolean fractionalQuantitiesEnabled,
                      Type type,UUID parentId,Map<String,String> attributes,boolean published,boolean everPublished) {
    public static final String DEFAULT_BASE_UNIT = "UNIT";
    private static final java.util.regex.Pattern BASE_UNIT = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final java.util.regex.Pattern ATTRIBUTE_CODE = java.util.regex.Pattern.compile("[a-z][a-z0-9_-]{0,79}");
    public enum Type { SIMPLE, VARIABLE, VARIANT, COMPOSITE }
    public enum Status {
        DRAFT, PENDING_RECEIPT, ACTIVE, INACTIVE, ARCHIVED;

        public boolean permitsTransitionTo(Status target) {
            if (target == null) return false;
            return switch (this) {
                case DRAFT -> target == PENDING_RECEIPT || target == ACTIVE || target == ARCHIVED;
                case PENDING_RECEIPT -> target == ACTIVE || target == INACTIVE || target == ARCHIVED;
                case ACTIVE -> target == INACTIVE || target == PENDING_RECEIPT || target == ARCHIVED;
                case INACTIVE -> target == ACTIVE || target == PENDING_RECEIPT || target == ARCHIVED;
                case ARCHIVED -> false;
            };
        }
    }
    public Product {
        Objects.requireNonNull(id);Objects.requireNonNull(tenantId);Objects.requireNonNull(categoryId);Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        requireSku(type,sku);
        requireName(name);
        attributes=variantAttributes(type,parentId,attributes);
        requireBaseUnit(baseUnit);
        if (published && (status != Status.ACTIVE || !everPublished))
            throw new IllegalArgumentException("Invalid publication state");
        if(version<0) throw new IllegalArgumentException("Invalid version");
    }
    public static void requireBaseUnit(String unit) {
        if(unit==null || !BASE_UNIT.matcher(unit).matches())
            throw new IllegalArgumentException("Invalid base unit");
    }

    public static void requireSku(Type type,String sku) {
        if (type==Type.VARIABLE ? sku!=null : (sku==null || sku.isBlank() || sku.length()>80))
            throw new IllegalArgumentException("Invalid SKU");
    }

    public static void requireName(String name) {
        if (name==null || name.isBlank() || name.length()>200)
            throw new IllegalArgumentException("Invalid name");
    }

    public static Map<String,String> variantAttributes(Type type,UUID parentId,Map<String,String> attributes) {
        if (type==null || (type==Type.VARIANT)!=(parentId!=null))
            throw new IllegalArgumentException("Invalid product parent");
        if (type!=Type.VARIANT) {
            if (attributes!=null && !attributes.isEmpty())
                throw new IllegalArgumentException("Attributes require a variant");
            return Map.of();
        }
        if (attributes==null || attributes.isEmpty())
            throw new IllegalArgumentException("Variant attributes are required");
        attributes.forEach((code,value) -> {
            if (code==null || !ATTRIBUTE_CODE.matcher(code).matches() || value==null || value.isBlank()
                    || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid variant attribute");
        });
        return Map.copyOf(attributes);
    }
}
