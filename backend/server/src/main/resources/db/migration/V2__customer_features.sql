-- Customer-facing features from the Rinse design: promos, favourites, saved places, problem reports,
-- notifications, outlet phone, review tags and reschedule support.

ALTER TABLE locations ADD COLUMN phone VARCHAR(32);
ALTER TABLE orders ADD COLUMN discount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN promo_code VARCHAR(40);
ALTER TABLE orders ADD COLUMN reminded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN reschedules INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reviews ADD COLUMN tags TEXT NOT NULL DEFAULT '[]';

CREATE TABLE promo_codes (
    tenant_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    discount_value BIGINT NOT NULL,
    max_discount_minor BIGINT,
    min_subtotal_minor BIGINT NOT NULL DEFAULT 0,
    starts_at BIGINT,
    expires_at BIGINT,
    usage_limit INTEGER,
    per_user_limit INTEGER NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(200) NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE favorites (
    user_id VARCHAR(36) NOT NULL,
    location_id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, location_id)
);

CREATE TABLE saved_places (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    label VARCHAR(40) NOT NULL,
    address VARCHAR(300) NOT NULL,
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    CONSTRAINT uq_place_label UNIQUE (user_id, label)
);

CREATE TABLE tickets (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    provider_id VARCHAR(36) NOT NULL,
    category VARCHAR(40) NOT NULL,
    description TEXT NOT NULL,
    photos TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    resolution_note TEXT,
    refund_minor BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    resolved_at BIGINT
);
CREATE INDEX ix_tickets_tenant ON tickets (tenant_id, status);
CREATE INDEX ix_tickets_user ON tickets (user_id);

CREATE TABLE notifications (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    order_id VARCHAR(36),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at);

CREATE TABLE devices (
    token VARCHAR(300) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL
);
