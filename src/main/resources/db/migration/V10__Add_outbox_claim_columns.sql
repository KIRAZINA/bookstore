-- V10: add claim-protocol columns to the outbox.
--
-- updated_at: the reaper needs a "when was this row last transitioned" timestamp
--             so it can distinguish fresh CLAIMED rows from stale ones. created_at
--             records insertion, not transition, and cannot serve.
-- claim_token: fencing token for Phase-3 (mark) writes. A worker that hangs past
--              the stale threshold, gets its claim reverted, and then writes a
--              late mark would otherwise overwrite a completed row. The mark
--              predicate requires (status=CLAIMED AND claim_token=:token), so a
--              zombie's mark updates zero rows and is logged as a fencing failure.
--
-- Backfill: rows that existed before this migration default to their last
-- meaningful transition timestamp (published_at for terminal rows, created_at
-- otherwise). Event volume at time of writing is small (no environment has more
-- than a few thousand rows); no batching is required.
--
-- updated_at is NOT NULL after backfill so a future code path cannot silently
-- insert rows the reaper would skip.

ALTER TABLE event_outbox ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE event_outbox ADD COLUMN claim_token UUID;
UPDATE event_outbox
   SET updated_at = COALESCE(published_at, created_at)
 WHERE updated_at IS NULL;
ALTER TABLE event_outbox ALTER COLUMN updated_at SET NOT NULL;
CREATE INDEX idx_event_outbox_status_updated
    ON event_outbox(status, updated_at);
