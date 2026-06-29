package com.example.flash_sale.product;

import java.io.Serializable;
import java.math.BigDecimal;

public record ProductDto(Long id, String sku, String name, String description, BigDecimal price,
                         BigDecimal flashSalePrice) implements Serializable {
    //TODO do i need to implement the Serializable here?
    public static ProductDto from(Product p) {
        return new ProductDto(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(), p.getPrice());
    }

    public static ProductDto from(Product p, BigDecimal flashSalePrice) {
        return new ProductDto(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(), flashSalePrice);

    }
}
