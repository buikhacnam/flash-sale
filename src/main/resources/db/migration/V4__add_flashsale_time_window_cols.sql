ALTER TABLE inventory
    ADD COLUMN flash_sale_starts_at TIMESTAMPTZ,
    ADD COLUMN flash_sale_ends_at TIMESTAMPTZ,
    ADD COLUMN flash_sale_price NUMERIC(12, 2) CHECK (flash_sale_price >= 0),
    ADD CONSTRAINT chk_flash_sale_duration CHECK (flash_sale_ends_at > flash_sale_starts_at);