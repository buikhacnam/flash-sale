package com.example.flash_sale.inventory;

import jakarta.persistence.*;

import java.math.BigDecimal;
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

    @Column(name = "flash_sale_starts_at")
    private Instant flashSaleStartsAt;

    @Column(name = "flash_sale_ends_at")
    private Instant flashSaleEndsAt;

    @Column(name = "flash_sale_price")
    private BigDecimal flashSalePrice;


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

    public Instant getFlashSaleStartsAt() {
        return flashSaleStartsAt;
    }

    public void setFlashSaleStartsAt(Instant flashSaleStartsAt) {
        this.flashSaleStartsAt = flashSaleStartsAt;
    }

    public Instant getFlashSaleEndsAt() {
        return flashSaleEndsAt;
    }

    public void setFlashSaleEndsAt(Instant flashSaleEndsAt) {
        this.flashSaleEndsAt = flashSaleEndsAt;
    }

    public BigDecimal getFlashSalePrice() {
        return flashSalePrice;
    }

    public void setFlashSalePrice(BigDecimal flashSalePrice) {
        this.flashSalePrice = flashSalePrice;
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
