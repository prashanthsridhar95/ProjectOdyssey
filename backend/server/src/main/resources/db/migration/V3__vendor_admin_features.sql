-- Vendor and admin features from the Rinse design: bays, recurring blocks, add-on items, review replies,
-- commission rules with effective dates, payout batches with hold and failure.

ALTER TABLE orders ADD COLUMN bay INTEGER;
ALTER TABLE blocks ADD COLUMN bays VARCHAR(100);
ALTER TABLE blocks ADD COLUMN repeat_rule VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE catalog_items ADD COLUMN bays_used INTEGER NOT NULL DEFAULT 1;
ALTER TABLE catalog_items ADD COLUMN out_of_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE catalog_items ADD COLUMN price_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reviews ADD COLUMN reply TEXT;
ALTER TABLE reviews ADD COLUMN reply_at BIGINT;
ALTER TABLE providers ADD COLUMN payout_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payouts ADD COLUMN reference VARCHAR(40);
ALTER TABLE payouts ADD COLUMN failure_reason VARCHAR(200);
ALTER TABLE payouts ADD COLUMN period_from BIGINT;
ALTER TABLE payouts ADD COLUMN period_to BIGINT;

CREATE TABLE commission_rules (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    target VARCHAR(100) NOT NULL,
    target_label VARCHAR(200) NOT NULL,
    rate_bps INTEGER NOT NULL,
    effective_from BIGINT NOT NULL,
    effective_until BIGINT,
    set_by VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX ix_rules_tenant ON commission_rules (tenant_id, scope);
