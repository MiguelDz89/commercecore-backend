-- CommerceCore MVP v1.1 - Evolución incremental sobre V1 + V2.
-- NO modificar V1/V2 si ya fueron aplicadas por Flyway.
-- Migración diseñada para PostgreSQL 15+; no sustituye las validaciones de dominio.
-- No activa RLS: requiere identidad de BD runtime separada y contexto por transacción.

-- Empresa: zona horaria para reportes y horarios de negocio; almacenar instantes en UTC.
ALTER TABLE tenant
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';
ALTER TABLE tenant
    ADD CONSTRAINT ck_tenant_time_zone_nonblank CHECK (btrim(time_zone) <> '');

-- Auditoría temporal básica de categorías.
ALTER TABLE category
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE category
    ADD CONSTRAINT ck_category_code_nonblank CHECK (btrim(code) <> ''),
    ADD CONSTRAINT ck_category_name_nonblank CHECK (btrim(name) <> '');

-- Protección de entradas de catálogo y estructura de composiciones.
ALTER TABLE app_role
    ADD CONSTRAINT ck_app_role_code_nonblank CHECK (btrim(code) <> '');
ALTER TABLE attribute_definition
    ADD CONSTRAINT ck_attribute_code_nonblank CHECK (btrim(code) <> '');
ALTER TABLE product_bom_item
    ADD CONSTRAINT ck_bom_unit_nonblank CHECK (btrim(unit_code) <> '');

-- Fotografías: checksum e identidad de quien realizó la subida.
-- Los objetos binarios siguen estando exclusivamente en Cloudflare R2.
ALTER TABLE product_image
    ADD COLUMN content_sha256 CHAR(64),
    ADD COLUMN uploaded_by UUID REFERENCES user_account(user_id);
ALTER TABLE product_image
    ADD CONSTRAINT ck_product_image_sha256_format
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-fA-F]{64}$');

-- Versiones de BOM: trazabilidad opcional del creador.
ALTER TABLE product_bom
    ADD COLUMN created_by UUID REFERENCES user_account(user_id);

-- Las importaciones masivas requieren un estado de procesamiento y resultado parcial.
-- Los nombres originales de constraints vienen de las CHECK inline de V1.
ALTER TABLE import_job
    DROP CONSTRAINT import_job_status_check;
ALTER TABLE import_job
    ADD CONSTRAINT ck_import_job_status_v2
        CHECK (status IN ('UPLOADED','VALIDATED','IMPORTING','PARTIAL','COMMITTED','FAILED'));

ALTER TABLE import_job_item
    DROP CONSTRAINT import_job_item_status_check;
ALTER TABLE import_job_item
    ADD CONSTRAINT ck_import_job_item_status_v2
        CHECK (status IN ('PENDING','VALID','INVALID','APPLIED','FAILED','SKIPPED'));
ALTER TABLE import_job_item
    ADD COLUMN applied_at TIMESTAMPTZ;

-- Consultas por estado de membresías y catálogo de vehículos.
CREATE INDEX ix_membership_tenant_status
    ON tenant_membership(tenant_id, status);

-- NOTA: ciclos en BOM / categorías, tenant autorizado y semántica de compatibilidad
-- deben validarse en la aplicación. RLS se implementará por una migración futura,
-- con rol de aplicación sin privilegios de propiedad/BYPASSRLS y tests de aislamiento.
