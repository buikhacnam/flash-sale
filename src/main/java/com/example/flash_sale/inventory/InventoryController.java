package com.example.flash_sale.inventory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/{productId}")
    public InventoryView update(@PathVariable Long productId, @Valid @RequestBody InventoryFlashSaleConfigRequest inventoryFlashSaleConfigRequest) {
        return inventoryService.updateFlashSaleConfigs(productId, inventoryFlashSaleConfigRequest);
    }

    @PostMapping("/flash-sale/load/{productId}")
    public Map<String, Object> loadFlashSale(@PathVariable Long productId) {
        int loaded = inventoryService.loadFlashSaleStock(productId);
        return Map.of("productId", productId, "loadedStock", loaded);
    }
}
