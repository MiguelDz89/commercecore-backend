-- Existing contracts counted generic units. Preserve all amounts and thresholds as stored.
ALTER TABLE tenant ADD COLUMN fractional_quantities_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE product
    ADD COLUMN base_unit VARCHAR(32) NOT NULL DEFAULT 'UNIT',
    ADD COLUMN fractional_quantities_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_product_base_unit CHECK (base_unit ~ '^[A-Z][A-Z0-9_]{0,31}$');
