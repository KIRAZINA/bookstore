package com.example.bookstore.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public class UpdateStockRequest {
    @NotNull(message = "Stock value is required")
    @PositiveOrZero(message = "Stock cannot be negative")
    private Integer stock;

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
}
