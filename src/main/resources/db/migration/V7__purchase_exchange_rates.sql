-- Exchange rates are tenant-specific. Updating a rate never changes recorded costs.
CREATE TABLE purchase_exchange_rate (
    exchange_rate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id),
    source_currency CHAR(3) NOT NULL CHECK (source_currency ~ '^[A-Z]{3}$'),
    target_currency CHAR(3) NOT NULL CHECK (target_currency ~ '^[A-Z]{3}$'),
    rate_date DATE NOT NULL,
    rate NUMERIC(18,8) NOT NULL CHECK (rate > 0 AND rate <> 'NaN'::numeric),
    recorded_by UUID NOT NULL REFERENCES user_account(user_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_currency <> target_currency),
    CONSTRAINT uq_purchase_exchange_rate_day UNIQUE (tenant_id,source_currency,target_currency,rate_date)
);

ALTER TABLE product
    ADD COLUMN cost_source_amount NUMERIC(18,2),
    ADD COLUMN cost_source_currency CHAR(3),
    ADD COLUMN cost_operation_date DATE,
    ADD COLUMN cost_rate_date DATE,
    ADD COLUMN cost_exchange_rate NUMERIC(18,8),
    ADD COLUMN cost_recorded_by UUID REFERENCES user_account(user_id),
    ADD CONSTRAINT ck_purchase_cost_evidence CHECK (
        (cost_source_amount IS NULL AND cost_source_currency IS NULL AND cost_operation_date IS NULL
            AND cost_rate_date IS NULL AND cost_exchange_rate IS NULL AND cost_recorded_by IS NULL)
        OR
        (cost_price IS NOT NULL AND cost_source_amount IS NOT NULL AND cost_source_amount >= 0
            AND cost_source_amount <> 'NaN'::numeric
            AND cost_source_currency IS NOT NULL AND cost_source_currency ~ '^[A-Z]{3}$'
            AND cost_operation_date IS NOT NULL AND cost_recorded_by IS NOT NULL
            AND (
                (cost_source_currency = currency_code AND cost_rate_date IS NULL AND cost_exchange_rate IS NULL
                    AND cost_price = cost_source_amount)
                OR
                (cost_source_currency <> currency_code AND cost_rate_date IS NOT NULL
                    AND cost_rate_date <= cost_operation_date AND cost_exchange_rate IS NOT NULL
                    AND cost_exchange_rate > 0 AND cost_exchange_rate <> 'NaN'::numeric
                    AND cost_price = round(cost_source_amount * cost_exchange_rate, 2))
            ))
    );

INSERT INTO permission (permission_code, description) VALUES
    ('PURCHASE_EXCHANGE_RATE_READ', 'Consultar tasas de cambio de compra'),
    ('PURCHASE_EXCHANGE_RATE_UPDATE', 'Registrar o corregir tasas de cambio de compra');
