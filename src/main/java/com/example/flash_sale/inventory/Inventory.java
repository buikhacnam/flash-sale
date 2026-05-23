package com.example.flash_sale.inventory;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "flash_sale_stock")
    private Integer flashSaleStock;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public Integer getFlashSaleStock() {
        return flashSaleStock;
    }

    public void decrementAvailable(int qty) {
        if (qty > availableStock) {
            throw new IllegalStateException("Insufficient PG stock for product " + productId);
        }
        this.availableStock -= qty;
    }

    public void incrementAvailable(int qty) {
        this.availableStock += qty;
    }
}
