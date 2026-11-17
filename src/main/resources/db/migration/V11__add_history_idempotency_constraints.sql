-- V11: structural hardening for once-per-lifetime order/payment history transitions.
--
-- The idempotency invariant is currently protected only implicitly: @Version optimistic
-- locking on Order/Payment and findByIdForUpdate on PaymentService serialize the existing
-- code paths. No structural constraint exists at the DB level, so a future code path that
-- bypasses the aggregate locks (for example a @Modifying bulk update or a read-modify-write
-- outside the checked path) could silently insert duplicate history rows. These indexes
-- close that gap.
--
-- "Once-per-lifetime" is derived per the (a)(b)(c) rule and verified against the
-- OrderStatus/PaymentStatus transition maps:
--   Order:   PAID, SHIPPED, DELIVERED, CANCELLED
--   Payment: CANCELLED, REFUNDED
-- OrderStatus is a DAG: none of those four states can be re-entered after leaving.
-- COMPLETED and FAILED are EXCLUDED from the Payment set because both are legitimately
-- re-enterable (refund-failure restore at PaymentService.java:364, and retry-then-fail).
--
-- Backfill (B1): both violation queries returned zero rows against the seeded Testcontainers
-- database before this migration was added, so no cleanup is required.
--   SELECT order_id, new_status, COUNT(*) FROM order_status_history
--    WHERE new_status IN ('PAID','SHIPPED','DELIVERED','CANCELLED')
--    GROUP BY order_id, new_status HAVING COUNT(*) > 1;
--   SELECT payment_id, new_status, COUNT(*) FROM payment_history
--    WHERE new_status IN ('CANCELLED','REFUNDED')
--    GROUP BY payment_id, new_status HAVING COUNT(*) > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_status_history_once_per_lifetime
    ON order_status_history (order_id, new_status)
    WHERE new_status IN ('PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_history_once_per_lifetime
    ON payment_history (payment_id, new_status)
    WHERE new_status IN ('CANCELLED', 'REFUNDED');
