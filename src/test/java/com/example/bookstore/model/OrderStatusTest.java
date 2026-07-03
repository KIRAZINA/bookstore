package com.example.bookstore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    @DisplayName("PENDING can transition to PAID")
    void pending_To_Paid() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID));
    }

    @Test
    @DisplayName("PENDING can transition to CANCELLED")
    void pending_To_Cancelled() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("PENDING is cancelable")
    void pending_IsCancelable() {
        assertTrue(OrderStatus.PENDING.isCancelable());
    }

    @Test
    @DisplayName("PENDING is not terminal")
    void pending_IsNotTerminal() {
        assertFalse(OrderStatus.PENDING.isTerminal());
    }

    @Test
    @DisplayName("PAID can transition to PROCESSING")
    void paid_To_Processing() {
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.PROCESSING));
    }

    @Test
    @DisplayName("PAID can transition to CANCELLED")
    void paid_To_Cancelled() {
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("PAID is cancelable")
    void paid_IsCancelable() {
        assertTrue(OrderStatus.PAID.isCancelable());
    }

    @Test
    @DisplayName("PAID is not terminal")
    void paid_IsNotTerminal() {
        assertFalse(OrderStatus.PAID.isTerminal());
    }

    @Test
    @DisplayName("PROCESSING can transition to SHIPPED")
    void processing_To_Shipped() {
        assertTrue(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.SHIPPED));
    }

    @Test
    @DisplayName("PROCESSING is not cancelable")
    void processing_IsNotCancelable() {
        assertFalse(OrderStatus.PROCESSING.isCancelable());
    }

    @Test
    @DisplayName("SHIPPED can transition to DELIVERED")
    void shipped_To_Delivered() {
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
    }

    @Test
    @DisplayName("SHIPPED is not cancelable")
    void shipped_IsNotCancelable() {
        assertFalse(OrderStatus.SHIPPED.isCancelable());
    }

    @Test
    @DisplayName("DELIVERED cannot transition anywhere")
    void delivered_NoTransitions() {
        for (OrderStatus target : OrderStatus.values()) {
            assertFalse(OrderStatus.DELIVERED.canTransitionTo(target));
        }
    }

    @Test
    @DisplayName("DELIVERED is terminal")
    void delivered_IsTerminal() {
        assertTrue(OrderStatus.DELIVERED.isTerminal());
    }

    @Test
    @DisplayName("DELIVERED is not cancelable")
    void delivered_IsNotCancelable() {
        assertFalse(OrderStatus.DELIVERED.isCancelable());
    }

    @Test
    @DisplayName("CANCELLED cannot transition anywhere")
    void cancelled_NoTransitions() {
        for (OrderStatus target : OrderStatus.values()) {
            assertFalse(OrderStatus.CANCELLED.canTransitionTo(target));
        }
    }

    @Test
    @DisplayName("CANCELLED is terminal")
    void cancelled_IsTerminal() {
        assertTrue(OrderStatus.CANCELLED.isTerminal());
    }

    @Test
    @DisplayName("CANCELLED is not cancelable")
    void cancelled_IsNotCancelable() {
        assertFalse(OrderStatus.CANCELLED.isCancelable());
    }

    @Test
    @DisplayName("PENDING cannot transition to DELIVERED directly")
    void pending_CannotSkipToDelivered() {
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED));
    }

    @Test
    @DisplayName("PAID cannot transition to SHIPPED directly")
    void paid_CannotSkipToShipped() {
        assertFalse(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED));
    }
}
