-- The V1 trigger is FOR EACH ROW on UPDATE/DELETE. TRUNCATE removes rows without
-- firing row triggers, so it needs its own statement-level trigger. This also
-- fires when ledger_entries is truncated via TRUNCATE ... CASCADE on a parent table.
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_change();
