-- The existing product identity remains the identity of each sellable SKU.
ALTER TABLE product
    DROP CONSTRAINT product_structure_type_check,
    DROP CONSTRAINT ck_product_nonblank_sku,
    ALTER COLUMN sku DROP NOT NULL,
    ADD COLUMN parent_id UUID,
    ADD COLUMN variant_attributes JSONB,
    ADD COLUMN parent_structure_type VARCHAR(20) GENERATED ALWAYS AS (
        CASE WHEN parent_id IS NOT NULL THEN 'VARIABLE' END
    ) STORED,
    ADD COLUMN sellable BOOLEAN GENERATED ALWAYS AS (structure_type <> 'VARIABLE') STORED,
    ADD CONSTRAINT ck_product_structure_type
        CHECK (structure_type IN ('SIMPLE', 'VARIABLE', 'VARIANT', 'COMPOSITE')),
    ADD CONSTRAINT ck_product_sku_by_structure CHECK (
        (structure_type = 'VARIABLE' AND sku IS NULL)
        OR (structure_type <> 'VARIABLE' AND sku IS NOT NULL AND btrim(sku) <> '')
    ),
    ADD CONSTRAINT ck_product_variant_shape CHECK (
        (structure_type = 'VARIANT' AND parent_id IS NOT NULL
            AND variant_attributes IS NOT NULL AND jsonb_typeof(variant_attributes) = 'object'
            AND variant_attributes <> '{}'::jsonb AND octet_length(variant_attributes::text) <= 2000
            AND NOT jsonb_path_exists(variant_attributes, '$.* ? (@.type() != "string")'))
        OR (structure_type <> 'VARIANT' AND parent_id IS NULL AND variant_attributes IS NULL)
    ),
    ADD CONSTRAINT ck_product_group_has_no_price CHECK (
        structure_type <> 'VARIABLE' OR
        (NOT sale_price_configured AND sale_price = 0 AND cost_price IS NULL)
    ),
    ADD CONSTRAINT uq_product_tenant_structure UNIQUE (tenant_id, product_id, structure_type),
    ADD CONSTRAINT uq_product_tenant_sellable UNIQUE (tenant_id, product_id, sellable),
    ADD CONSTRAINT fk_product_variant_parent FOREIGN KEY (tenant_id, parent_id, parent_structure_type)
        REFERENCES product(tenant_id, product_id, structure_type);

CREATE UNIQUE INDEX uq_product_variant_attributes
    ON product(tenant_id, parent_id, variant_attributes)
    WHERE parent_id IS NOT NULL;

ALTER TABLE product_price_tier
    ADD COLUMN sellable BOOLEAN GENERATED ALWAYS AS (true) STORED,
    ADD CONSTRAINT fk_price_tier_sellable_product FOREIGN KEY (tenant_id, product_id, sellable)
        REFERENCES product(tenant_id, product_id, sellable);
