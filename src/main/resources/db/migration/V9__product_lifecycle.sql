-- Preserve every existing product state and publication setting.
ALTER TABLE product DROP CONSTRAINT product_status_check;
ALTER TABLE product ADD CONSTRAINT product_status_check
    CHECK (status IN ('DRAFT', 'PENDING_RECEIPT', 'ACTIVE', 'INACTIVE', 'ARCHIVED'));

INSERT INTO permission (permission_code, description)
VALUES ('PRODUCT_STATUS_UPDATE', 'Cambiar el estado de productos');
