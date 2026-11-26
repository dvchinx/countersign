-- Countersign receipts, append-only. PostgreSQL 14 or later.
--
-- Run it with psql, Flyway or Liquibase, or let JdbcAuditSink.createSchema(dataSource) run it at startup
-- (countersign.audit.jdbc.initialize-schema=true, the default). See docs/adr/0010-jdbc-audit-sink.md.
--
-- One row per receipt. The columns hold the receipt fields exactly as they were hashed and signed, so a stream
-- read back in sequence_number order verifies with ReceiptVerifier.

CREATE TABLE IF NOT EXISTS countersign_receipt (
    stream_id        text        NOT NULL,
    sequence_number  bigint      NOT NULL CHECK (sequence_number >= 1),
    invocation_id    text        NOT NULL,
    tool             text        NOT NULL,
    args_hmac        text        NOT NULL,
    decision         text        NOT NULL,
    reason           text,
    basis            text        NOT NULL,
    matched_policies text[]      NOT NULL,
    approval         text,
    approver         text,
    principal        text,
    conversation_id  text,
    created_at       timestamptz NOT NULL,
    previous_hash    text        NOT NULL,
    hash             text        NOT NULL,
    signature        text        NOT NULL,
    PRIMARY KEY (stream_id, sequence_number)
);

-- Append-only. The signatures and the hash chain already make any change detectable; these triggers stop accidental
-- changes from happening at all. For a stronger guarantee, grant the application role only INSERT and SELECT on
-- this table, so it cannot drop the triggers either.
CREATE OR REPLACE FUNCTION countersign_receipt_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'countersign_receipt is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE OR REPLACE TRIGGER countersign_receipt_no_update_or_delete
    BEFORE UPDATE OR DELETE ON countersign_receipt
    FOR EACH ROW EXECUTE FUNCTION countersign_receipt_append_only();

CREATE OR REPLACE TRIGGER countersign_receipt_no_truncate
    BEFORE TRUNCATE ON countersign_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION countersign_receipt_append_only();
