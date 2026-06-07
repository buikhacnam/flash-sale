ALTER TABLE orders
    ADD COLUMN expires_at TIMESTAMPTZ;

CREATE INDEX idx_orders_status_expires_at ON orders(status, expires_at);
