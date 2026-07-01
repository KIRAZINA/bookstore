package com.example.bookstore.service.payment;

import com.example.bookstore.model.Payment;
import com.example.bookstore.model.PaymentStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DummyPaymentProvider implements PaymentProvider {

    @Override
    public String getName() {
        return "DUMMY";
    }

    @Override
    public PaymentProviderResponse processPayment(Payment payment) {
        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return PaymentProviderResponse.success(transactionId, PaymentStatus.COMPLETED);
    }

    @Override
    public PaymentProviderResponse refundPayment(Payment payment) {
        String transactionId = payment.getTransactionId() + "-REF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return PaymentProviderResponse.success(transactionId, PaymentStatus.REFUNDED);
    }
}
