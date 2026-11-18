-- V12: refund record integrity (D-REFUND-1 / M19, Wave 4D).
--
-- The refund path previously overwrote payment.transaction_id with the refund's
-- provider transaction id, destroying the original payment identifier. These nullable
-- columns let transaction_id survive the refund while the refund's own amount,
-- timestamp and provider transaction id are retained on the payment record.
--
-- All three columns are nullable:
--   * a payment that was never refunded leaves them NULL;
--   * a failed refund attempt leaves them NULL (only the state returns to COMPLETED).
-- No backfill: historically refunded payments retain the overwritten transaction_id
-- and have NULL refund fields ("not preserved historically"). Recovering those ids
-- requires the provider ledger and is out of scope for this wave.
--
-- No CHECK constraint on refunded_amount: the "full payment amount on success, NULL on
-- failure" rule is enforced in code (PaymentService.refundPaymentPhase3).
-- No index: none of these columns is used as a query predicate.

ALTER TABLE payment ADD COLUMN refunded_amount NUMERIC(19,2);
ALTER TABLE payment ADD COLUMN refunded_at TIMESTAMP;
ALTER TABLE payment ADD COLUMN refund_transaction_id VARCHAR(255);
