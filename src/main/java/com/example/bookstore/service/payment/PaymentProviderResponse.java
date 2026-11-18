package com.example.bookstore.service.payment;

import com.example.bookstore.model.PaymentStatus;

public class PaymentProviderResponse {
    private boolean successful;
    private String transactionId;
    private String failureReason;

    public static PaymentProviderResponse success(String transactionId, PaymentStatus newStatus) {
        PaymentProviderResponse r = new PaymentProviderResponse();
        r.successful = true;
        r.transactionId = transactionId;
        return r;
    }

    public static PaymentProviderResponse failed(String failureReason) {
        PaymentProviderResponse r = new PaymentProviderResponse();
        r.successful = false;
        r.failureReason = failureReason;
        return r;
    }

    public boolean isSuccess() { return successful; }
    public String getTransactionId() { return transactionId; }
    public String getFailureReason() { return failureReason; }
}
