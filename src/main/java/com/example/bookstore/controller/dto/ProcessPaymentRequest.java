package com.example.bookstore.controller.dto;

import jakarta.validation.constraints.NotBlank;

public class ProcessPaymentRequest {
    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
