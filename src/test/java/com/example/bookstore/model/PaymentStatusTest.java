package com.example.bookstore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentStatusTest {

    @Test
    @DisplayName("PENDING can transition to PROCESSING")
    void pending_CanTransitionTo_Processing() {
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PROCESSING));
    }

    @Test
    @DisplayName("PENDING can transition to CANCELLED")
    void pending_CanTransitionTo_Cancelled() {
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED));
    }

    @Test
    @DisplayName("PENDING cannot transition to COMPLETED")
    void pending_CannotTransitionTo_Completed() {
        assertFalse(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.COMPLETED));
    }

    @Test
    @DisplayName("PENDING cannot transition to REFUNDED")
    void pending_CannotTransitionTo_Refunded() {
        assertFalse(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("PROCESSING can transition to COMPLETED")
    void processing_CanTransitionTo_Completed() {
        assertTrue(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.COMPLETED));
    }

    @Test
    @DisplayName("PROCESSING can transition to FAILED")
    void processing_CanTransitionTo_Failed() {
        assertTrue(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("PROCESSING cannot transition to CANCELLED")
    void processing_CannotTransitionTo_Cancelled() {
        assertFalse(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.CANCELLED));
    }

    @Test
    @DisplayName("COMPLETED can transition to REFUNDED")
    void completed_CanTransitionTo_Refunded() {
        assertTrue(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("COMPLETED cannot transition to anything else")
    void completed_CannotTransitionTo_Other() {
        assertFalse(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.PENDING));
        assertFalse(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.CANCELLED));
        assertFalse(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("FAILED can transition back to PENDING")
    void failed_CanTransitionTo_Pending() {
        assertTrue(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PENDING));
    }

    @Test
    @DisplayName("FAILED cannot transition to COMPLETED directly")
    void failed_CannotTransitionTo_Completed() {
        assertFalse(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.COMPLETED));
    }

    @Test
    @DisplayName("CANCELLED is terminal")
    void cancelled_IsTerminal() {
        assertTrue(PaymentStatus.CANCELLED.isTerminal());
    }

    @Test
    @DisplayName("CANCELLED cannot transition anywhere")
    void cancelled_HasNoTransitions() {
        assertFalse(PaymentStatus.CANCELLED.canTransitionTo(PaymentStatus.PENDING));
        assertFalse(PaymentStatus.CANCELLED.canTransitionTo(PaymentStatus.PROCESSING));
        assertFalse(PaymentStatus.CANCELLED.canTransitionTo(PaymentStatus.COMPLETED));
    }

    @Test
    @DisplayName("REFUNDED is terminal")
    void refunded_IsTerminal() {
        assertTrue(PaymentStatus.REFUNDED.isTerminal());
    }

    @Test
    @DisplayName("COMPLETED is terminal")
    void completed_IsTerminal() {
        assertTrue(PaymentStatus.COMPLETED.isTerminal());
    }

    @Test
    @DisplayName("PENDING is not terminal")
    void pending_IsNotTerminal() {
        assertFalse(PaymentStatus.PENDING.isTerminal());
    }

    @Test
    @DisplayName("PROCESSING is not terminal")
    void processing_IsNotTerminal() {
        assertFalse(PaymentStatus.PROCESSING.isTerminal());
    }

    @Test
    @DisplayName("FAILED is not terminal")
    void failed_IsNotTerminal() {
        assertFalse(PaymentStatus.FAILED.isTerminal());
    }
}
