-- Capture the originating OpenTelemetry span ID alongside the trace ID so that
-- the outbox publisher can inject a W3C traceparent header when forwarding events
-- to Kafka, letting consumers continue the end-to-end trace.
ALTER TABLE event_outbox ADD COLUMN span_id VARCHAR(32);