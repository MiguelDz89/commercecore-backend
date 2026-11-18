ALTER TABLE product
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ever_published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_product_published_active CHECK (NOT published OR status = 'ACTIVE'),
    ADD CONSTRAINT ck_product_published_history CHECK (NOT published OR ever_published);
