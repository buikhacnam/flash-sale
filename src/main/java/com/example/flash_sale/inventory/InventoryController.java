package com.example.flash_sale.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}")
    public InventoryView get(@PathVariable Long productId) {
        return inventoryService.getView(productId);
    }

    @PostMapping("/flash-sale/load/{productId}")
    public Map<String, Object> loadFlashSale(@PathVariable Long productId) {
        int loaded = inventoryService.loadFlashSaleStock(productId);
        return Map.of("productId", productId, "loadedStock", loaded);
    }
}
