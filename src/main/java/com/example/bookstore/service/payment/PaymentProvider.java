package com.example.bookstore.service.payment;

import com.example.bookstore.model.Payment;

public interface PaymentProvider {
    String getName();
    PaymentProviderResponse processPayment(Payment payment);
    PaymentProviderResponse refundPayment(Payment payment);
}
