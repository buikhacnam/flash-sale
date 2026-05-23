-- Users (5)
INSERT INTO users (email, name) VALUES
  ('alice@example.com',   'Alice'),
  ('bob@example.com',     'Bob'),
  ('carol@example.com',   'Carol'),
  ('dave@example.com',    'Dave'),
  ('erin@example.com',    'Erin');

-- Products (20)
INSERT INTO products (sku, name, description, price) VALUES
  ('SKU-001', 'Mechanical Keyboard',     'Hot-swappable 75% layout',           149.99),
  ('SKU-002', 'Wireless Mouse',          '4000 DPI ergonomic mouse',            59.99),
  ('SKU-003', '27" Monitor',             '1440p IPS 165Hz',                    329.00),
  ('SKU-004', 'USB-C Hub',               '8-in-1 dock',                         49.50),
  ('SKU-005', 'Noise Cancelling Headphones', 'Over-ear, 40h battery',          249.00),
  ('SKU-006', 'Mechanical Pencil',       '0.5mm precision drafting',             7.50),
  ('SKU-007', 'Standing Desk',           'Electric, dual motor',               499.00),
  ('SKU-008', 'Office Chair',            'Mesh ergonomic',                     299.00),
  ('SKU-009', 'Webcam 1080p',            'Auto-focus, stereo mic',              79.00),
  ('SKU-010', 'Ring Light',              '10" adjustable color temp',           39.00),
  ('SKU-011', 'External SSD 1TB',        'USB 3.2 portable',                   119.00),
  ('SKU-012', 'Mouse Pad XL',            'Stitched edge, water resistant',      19.99),
  ('SKU-013', 'Laptop Stand',            'Aluminium adjustable',                34.99),
  ('SKU-014', 'Wireless Charger',        '15W fast charge',                     29.99),
  ('SKU-015', 'Cable Organizer Kit',     'Velcro + clips bundle',                9.99),
  ('SKU-016', 'Bluetooth Speaker',       'Portable 20W, IPX7',                  89.00),
  ('SKU-017', 'Smart Plug',              'Wi-Fi controllable',                  14.99),
  ('SKU-018', 'LED Strip 5m',            'RGB Wi-Fi controllable',              24.99),
  ('SKU-019', 'Reusable Water Bottle',   'Insulated 750ml',                     22.00),
  ('SKU-020', 'Coffee Beans 1kg',        'Single origin Ethiopian',             28.00);

-- Inventory rows for all products. Default available stock 1000.
INSERT INTO inventory (product_id, available_stock, flash_sale_stock)
SELECT id, 1000, NULL FROM products;

-- Pre-set flash-sale stock columns for 3 products. The Redis counter is still loaded
-- on demand via POST /api/inventory/flash-sale/load/{productId}.
UPDATE inventory SET flash_sale_stock = 10 WHERE product_id = 1;
UPDATE inventory SET flash_sale_stock = 20 WHERE product_id = 2;
UPDATE inventory SET flash_sale_stock = 5  WHERE product_id = 3;
