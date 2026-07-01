package com.example.bookstore.service.payment;

import com.example.bookstore.model.PaymentStatus;

public class PaymentProviderResponse {
    private boolean success;
    private String transactionId;
    private String failureReason;
    private PaymentStatus newStatus;

    public static PaymentProviderResponse success(String transactionId, PaymentStatus newStatus) {
        PaymentProviderResponse r = new PaymentProviderResponse();
        r.success = true;
        r.transactionId = transactionId;
        r.newStatus = newStatus;
        return r;
    }

    public static PaymentProviderResponse failed(String failureReason) {
        PaymentProviderResponse r = new PaymentProviderResponse();
        r.success = false;
        r.failureReason = failureReason;
        r.newStatus = PaymentStatus.FAILED;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public PaymentStatus getNewStatus() { return newStatus; }
    public void setNewStatus(PaymentStatus newStatus) { this.newStatus = newStatus; }
}
