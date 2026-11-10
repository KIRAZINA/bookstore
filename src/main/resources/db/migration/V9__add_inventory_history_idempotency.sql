-- Enforce idempotent inventory history for terminal mutation events.
-- Prevents concurrent duplicate release, restore, or finalize rows for the
-- same book+order pair. RESERVED is intentionally excluded because a book can
-- be re-reserved for an order after a release.
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_history_idempotency
    ON inventory_history (book_id, order_id, event_type)
    WHERE event_type IN ('RELEASED', 'RESTORED', 'FINALIZED');
