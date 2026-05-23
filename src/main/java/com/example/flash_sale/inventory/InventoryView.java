package com.example.flash_sale.inventory;

public record InventoryView(Long productId,
                            int availableStock,
                            Integer flashSaleStockConfigured,
                            Integer flashSaleStockRemaining) {
}
