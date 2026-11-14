-- CommerceCore · PostgreSQL 15+ · MVP schema 1.0
-- Monolito modular, multiempresa mediante tenant_id.
-- Ejecutar mediante Flyway sobre una base VACÍA. NO incluye CREATE DATABASE.
-- NO activa RLS todavía: hacerlo solo cuando Quarkus propague el contexto
-- de empresa dentro de la misma transacción JDBC y exista un usuario
-- de ejecución diferente del propietario de las tablas.

-- 01. EMPRESAS
CREATE TABLE tenant (
    tenant_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           VARCHAR(80) NOT NULL UNIQUE,
    legal_name     VARCHAR(200) NOT NULL,
    display_name   VARCHAR(160) NOT NULL,
    currency_code  CHAR(3) NOT NULL DEFAULT 'PEN',
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

-- 02. IDENTIDAD GLOBAL: usuarios autenticados por proveedor externo OIDC
CREATE TABLE user_account (
    user_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    oidc_issuer    VARCHAR(300) NOT NULL,
    oidc_subject   VARCHAR(255) NOT NULL,
    email          VARCHAR(320),
    display_name   VARCHAR(180),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_oidc UNIQUE (oidc_issuer, oidc_subject)
);

-- 03. PERTENENCIA DE UN USUARIO A UNA EMPRESA
CREATE TABLE tenant_membership (
    membership_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id),
    user_id        UUID NOT NULL REFERENCES user_account(user_id),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT uq_membership_tenant_id UNIQUE (tenant_id, membership_id)
);
CREATE INDEX ix_membership_user ON tenant_membership(user_id, status);

-- 04. ROLES, PERMISOS Y ASIGNACIONES POR EMPRESA
CREATE TABLE app_role (
    role_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id),
    code           VARCHAR(80) NOT NULL,
    name           VARCHAR(120) NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_role_tenant_id UNIQUE (tenant_id, role_id)
);

CREATE TABLE permission (
    permission_code VARCHAR(100) PRIMARY KEY,
    description     VARCHAR(300) NOT NULL
);

CREATE TABLE role_permission (
    tenant_id        UUID NOT NULL,
    role_id          UUID NOT NULL,
    permission_code  VARCHAR(100) NOT NULL REFERENCES permission(permission_code),
    PRIMARY KEY (tenant_id, role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (tenant_id, role_id)
      REFERENCES app_role(tenant_id, role_id) ON DELETE CASCADE
);

CREATE TABLE membership_role (
    tenant_id       UUID NOT NULL,
    membership_id   UUID NOT NULL,
    role_id         UUID NOT NULL,
    PRIMARY KEY (tenant_id, membership_id, role_id),
    CONSTRAINT fk_membership_role_member FOREIGN KEY (tenant_id, membership_id)
      REFERENCES tenant_membership(tenant_id, membership_id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role_role FOREIGN KEY (tenant_id, role_id)
      REFERENCES app_role(tenant_id, role_id) ON DELETE CASCADE
);

-- 05. CAPACIDADES ACTIVADAS POR EMPRESA (AUTOMOTRIZ, ETC.)
CREATE TABLE tenant_feature (
    tenant_id       UUID NOT NULL REFERENCES tenant(tenant_id),
    feature_code    VARCHAR(80) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, feature_code)
);

-- 06. CATEGORÍAS
CREATE TABLE category (
    category_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(tenant_id),
    parent_category_id UUID,
    code            VARCHAR(80) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_category_tenant_id UNIQUE (tenant_id, category_id),
    CONSTRAINT ck_category_not_own_parent CHECK (parent_category_id IS NULL OR parent_category_id <> category_id),
    CONSTRAINT fk_category_parent_tenant FOREIGN KEY (tenant_id, parent_category_id)
      REFERENCES category(tenant_id, category_id)
);
CREATE INDEX ix_category_tenant_parent ON category(tenant_id, parent_category_id);

-- 07. PRODUCTOS (SIMPLES Y COMPUESTOS) Y METADATOS SEO
CREATE TABLE product (
    product_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(tenant_id),
    category_id      UUID NOT NULL,
    sku              VARCHAR(80) NOT NULL,
    sku_normalized   VARCHAR(80) GENERATED ALWAYS AS (upper(btrim(sku))) STORED,
    name             VARCHAR(200) NOT NULL,
    description      TEXT,
    structure_type   VARCHAR(20) NOT NULL DEFAULT 'SIMPLE'
                     CHECK (structure_type IN ('SIMPLE', 'COMPOSITE')),
    fulfillment_mode VARCHAR(24) NOT NULL DEFAULT 'STANDARD'
                     CHECK (fulfillment_mode IN ('STANDARD', 'COMPONENT_PICK', 'PREASSEMBLED', 'MAKE_TO_ORDER')),
    sale_price       NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (sale_price >= 0),
    currency_code    CHAR(3) NOT NULL DEFAULT 'PEN',
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    seo_title        VARCHAR(180),
    seo_description  VARCHAR(350),
    slug             VARCHAR(220),
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       UUID REFERENCES user_account(user_id),
    updated_by       UUID REFERENCES user_account(user_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_product_nonblank_sku CHECK (btrim(sku) <> ''),
    CONSTRAINT ck_product_nonblank_name CHECK (btrim(name) <> ''),
    CONSTRAINT uq_product_tenant_sku UNIQUE (tenant_id, sku_normalized),
    CONSTRAINT uq_product_tenant_id UNIQUE (tenant_id, product_id),
    CONSTRAINT fk_product_category_tenant FOREIGN KEY (tenant_id, category_id)
      REFERENCES category(tenant_id, category_id)
);
CREATE INDEX ix_product_tenant_status_category ON product(tenant_id, status, category_id);
CREATE INDEX ix_product_tenant_name ON product(tenant_id, name);
CREATE UNIQUE INDEX uq_product_tenant_slug ON product(tenant_id, lower(slug)) WHERE slug IS NOT NULL;

-- 08. ATRIBUTOS CONFIGURABLES (POR EMPRESA Y OPCIONALMENTE CATEGORÍA)
CREATE TABLE attribute_definition (
    attribute_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(tenant_id),
    category_id      UUID,
    code             VARCHAR(80) NOT NULL,
    name             VARCHAR(140) NOT NULL,
    data_type        VARCHAR(20) NOT NULL
                     CHECK (data_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'DATE', 'OPTION')),
    required         BOOLEAN NOT NULL DEFAULT FALSE,
    options_json     JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attr_tenant_id UNIQUE (tenant_id, attribute_id),
    CONSTRAINT fk_attr_category_tenant FOREIGN KEY (tenant_id, category_id)
      REFERENCES category(tenant_id, category_id)
);
CREATE UNIQUE INDEX uq_attr_global_code ON attribute_definition(tenant_id, code)
    WHERE category_id IS NULL;
CREATE UNIQUE INDEX uq_attr_category_code ON attribute_definition(tenant_id, category_id, code)
    WHERE category_id IS NOT NULL;

CREATE TABLE product_attribute_value (
    tenant_id        UUID NOT NULL,
    product_id       UUID NOT NULL,
    attribute_id     UUID NOT NULL,
    value_json       JSONB NOT NULL,
    PRIMARY KEY (tenant_id, product_id, attribute_id),
    CONSTRAINT fk_pav_product FOREIGN KEY (tenant_id, product_id)
      REFERENCES product(tenant_id, product_id) ON DELETE CASCADE,
    CONSTRAINT fk_pav_definition FOREIGN KEY (tenant_id, attribute_id)
      REFERENCES attribute_definition(tenant_id, attribute_id)
);

-- 09. LISTA DE MATERIALES VERSIONADA (BOM)
CREATE TABLE product_bom (
    bom_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    product_id        UUID NOT NULL,
    bom_version       INTEGER NOT NULL CHECK (bom_version > 0),
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bom_tenant_id UNIQUE (tenant_id, bom_id),
    CONSTRAINT uq_bom_version UNIQUE (tenant_id, product_id, bom_version),
    CONSTRAINT fk_bom_product FOREIGN KEY (tenant_id, product_id)
      REFERENCES product(tenant_id, product_id)
);
CREATE UNIQUE INDEX uq_bom_active_product ON product_bom(tenant_id, product_id)
    WHERE status = 'ACTIVE';

CREATE TABLE product_bom_item (
    bom_item_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    bom_id            UUID NOT NULL,
    component_product_id UUID NOT NULL,
    quantity          NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit_code         VARCHAR(20) NOT NULL DEFAULT 'UND',
    CONSTRAINT uq_bom_item_component UNIQUE (tenant_id, bom_id, component_product_id),
    CONSTRAINT fk_bom_item_header FOREIGN KEY (tenant_id, bom_id)
      REFERENCES product_bom(tenant_id, bom_id) ON DELETE CASCADE,
    CONSTRAINT fk_bom_item_product FOREIGN KEY (tenant_id, component_product_id)
      REFERENCES product(tenant_id, product_id)
);
CREATE INDEX ix_bom_item_component ON product_bom_item(tenant_id, component_product_id);

-- 10. FOTOGRAFÍAS EN CLOUDFLARE R2: SOLO METADATOS, NO BINARIOS
CREATE TABLE product_image (
    image_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    product_id        UUID NOT NULL,
    storage_provider  VARCHAR(30) NOT NULL DEFAULT 'R2',
    bucket_name       VARCHAR(140) NOT NULL,
    object_key        VARCHAR(800) NOT NULL,
    content_type      VARCHAR(100),
    byte_size         BIGINT CHECK (byte_size IS NULL OR byte_size >= 0),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    sort_order        INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    is_primary        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_image_storage_key UNIQUE (storage_provider, bucket_name, object_key),
    CONSTRAINT fk_image_product FOREIGN KEY (tenant_id, product_id)
      REFERENCES product(tenant_id, product_id) ON DELETE CASCADE,
    CONSTRAINT ck_image_ready_metadata CHECK (
      status <> 'READY' OR (content_type IS NOT NULL AND byte_size IS NOT NULL)
    )
);
CREATE INDEX ix_product_image_product ON product_image(tenant_id, product_id, sort_order);
CREATE UNIQUE INDEX uq_product_primary_image ON product_image(tenant_id, product_id)
    WHERE is_primary = TRUE AND status = 'READY';

-- 11. COMPATIBILIDAD VEHICULAR (CAPACIDAD OPCIONAL POR EMPRESA)
CREATE TABLE vehicle_make (
    make_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(120) NOT NULL
);
CREATE UNIQUE INDEX uq_vehicle_make_name ON vehicle_make(lower(name));

CREATE TABLE vehicle_model (
    vehicle_model_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(tenant_id),
    make_id           UUID NOT NULL REFERENCES vehicle_make(make_id),
    model_name        VARCHAR(140) NOT NULL,
    variant           VARCHAR(140) NOT NULL DEFAULT '',
    engine_cc         INTEGER CHECK (engine_cc IS NULL OR engine_cc > 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vehicle_model_tenant_id UNIQUE (tenant_id, vehicle_model_id)
);
CREATE UNIQUE INDEX uq_vehicle_model_business_key
    ON vehicle_model(tenant_id, make_id, lower(model_name), lower(variant), COALESCE(engine_cc, -1));

CREATE TABLE product_vehicle_compatibility (
    compatibility_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    product_id        UUID NOT NULL,
    vehicle_model_id  UUID NOT NULL,
    year_from         INTEGER NOT NULL CHECK (year_from BETWEEN 1900 AND 2200),
    year_to           INTEGER NOT NULL CHECK (year_to BETWEEN 1900 AND 2200),
    notes             TEXT,
    CONSTRAINT ck_compat_years CHECK (year_to >= year_from),
    CONSTRAINT uq_compat UNIQUE (tenant_id, product_id, vehicle_model_id, year_from, year_to),
    CONSTRAINT fk_compat_product FOREIGN KEY (tenant_id, product_id)
      REFERENCES product(tenant_id, product_id) ON DELETE CASCADE,
    CONSTRAINT fk_compat_vehicle FOREIGN KEY (tenant_id, vehicle_model_id)
      REFERENCES vehicle_model(tenant_id, vehicle_model_id)
);
CREATE INDEX ix_compat_vehicle ON product_vehicle_compatibility(tenant_id, vehicle_model_id);

-- 12. IMPORTACIÓN DE EXCEL Y RESULTADOS POR FILA
CREATE TABLE import_job (
    import_job_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(tenant_id),
    requested_by      UUID REFERENCES user_account(user_id),
    original_filename VARCHAR(255) NOT NULL,
    file_sha256       CHAR(64),
    import_mode       VARCHAR(20) NOT NULL DEFAULT 'UPSERT'
                      CHECK (import_mode IN ('INSERT_ONLY', 'UPSERT')),
    status            VARCHAR(20) NOT NULL DEFAULT 'UPLOADED'
                      CHECK (status IN ('UPLOADED', 'VALIDATED', 'COMMITTED', 'FAILED')),
    total_rows        INTEGER NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    success_rows      INTEGER NOT NULL DEFAULT 0 CHECK (success_rows >= 0),
    error_rows        INTEGER NOT NULL DEFAULT 0 CHECK (error_rows >= 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    CONSTRAINT uq_import_tenant_id UNIQUE (tenant_id, import_job_id)
);
CREATE INDEX ix_import_tenant_created ON import_job(tenant_id, created_at DESC);

CREATE TABLE import_job_item (
    item_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    import_job_id     UUID NOT NULL,
    sheet_name        VARCHAR(90) NOT NULL,
    row_number        INTEGER NOT NULL CHECK (row_number >= 1),
    source_sku        VARCHAR(80),
    raw_data          JSONB,
    validation_errors JSONB,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'VALID', 'INVALID', 'APPLIED')),
    CONSTRAINT uq_import_sheet_row UNIQUE (tenant_id, import_job_id, sheet_name, row_number),
    CONSTRAINT fk_import_item_job FOREIGN KEY (tenant_id, import_job_id)
      REFERENCES import_job(tenant_id, import_job_id) ON DELETE CASCADE
);

-- 13. AUDITORÍA DE NEGOCIO
CREATE TABLE audit_event (
    audit_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(tenant_id),
    actor_user_id     UUID REFERENCES user_account(user_id),
    entity_type       VARCHAR(100) NOT NULL,
    entity_id         UUID NOT NULL,
    action            VARCHAR(100) NOT NULL,
    before_data       JSONB,
    after_data        JSONB,
    correlation_id    VARCHAR(100),
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_entity ON audit_event(tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_tenant_time ON audit_event(tenant_id, occurred_at DESC);
