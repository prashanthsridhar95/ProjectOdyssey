-- Pricing promotions: happy hours and date discounts, vouchers funded by a vendor or the platform, and refer-a-friend.

ALTER TABLE orders ADD COLUMN discounts TEXT NOT NULL DEFAULT '[]';
ALTER TABLE orders ADD COLUMN vendor_discount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN promotion_id VARCHAR(36);

ALTER TABLE promo_codes ADD COLUMN provider_id VARCHAR(36);
ALTER TABLE promo_codes ADD COLUMN funded_by VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';
ALTER TABLE promo_codes ADD COLUMN owner_user_id VARCHAR(36);
ALTER TABLE promo_codes ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE promo_codes ADD COLUMN created_at BIGINT;
CREATE INDEX ix_promo_owner ON promo_codes (owner_user_id);

CREATE TABLE promotions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    provider_id VARCHAR(36),
    promo_type VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    discount_value BIGINT NOT NULL,
    max_discount_minor BIGINT,
    location_ids TEXT NOT NULL,
    services TEXT NOT NULL,
    variant_keys TEXT NOT NULL,
    days VARCHAR(40) NOT NULL,
    start_time VARCHAR(5),
    end_time VARCHAR(5),
    start_date VARCHAR(10),
    end_date VARCHAR(10),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(200) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX ix_promotions_tenant ON promotions (tenant_id, provider_id);

CREATE TABLE referral_codes (
    user_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX ux_referral_code ON referral_codes (tenant_id, code);

CREATE TABLE referrals (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    referrer_id VARCHAR(36) NOT NULL,
    referee_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    welcome_voucher VARCHAR(40),
    reward_voucher VARCHAR(40),
    created_at BIGINT NOT NULL,
    rewarded_at BIGINT
);
CREATE UNIQUE INDEX ux_referral_referee ON referrals (referee_id);
CREATE INDEX ix_referral_referrer ON referrals (referrer_id);
