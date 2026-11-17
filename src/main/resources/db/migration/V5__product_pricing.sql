-- Historical sale_price values are preserved but require explicit confirmation.
ALTER TABLE product ADD COLUMN sale_price_configured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN cost_price NUMERIC(18,2)
    CHECK (cost_price IS NULL OR (cost_price >= 0 AND cost_price <> 'NaN'::numeric));

CREATE TABLE product_price_tier (
    price_tier_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    minimum_quantity NUMERIC(18,3) NOT NULL CHECK (minimum_quantity > 0 AND minimum_quantity <> 'NaN'::numeric),
    unit_price NUMERIC(18,2) NOT NULL CHECK (unit_price >= 0 AND unit_price <> 'NaN'::numeric),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_tier_quantity UNIQUE (tenant_id, product_id, minimum_quantity),
    CONSTRAINT fk_price_tier_product FOREIGN KEY (tenant_id, product_id) REFERENCES product(tenant_id, product_id)
);
INSERT INTO permission (permission_code, description) VALUES
 ('PRODUCT_PRICE_TIER_READ', 'Consultar precios escalonados'),
 ('PRODUCT_COST_READ', 'Consultar costes de productos'),
 ('PRODUCT_COST_UPDATE', 'Modificar costes de productos');
