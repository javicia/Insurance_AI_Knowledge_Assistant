-- FASE 14 audit remediation: ai_audit_records had indexes on trace_id and occurred_at (V5) but
-- not on the ai_system_id foreign key column - a minor omission noted during the independent
-- architecture audit (finding DB-01, docs/adr/ADR-012-AUDIT-REMEDIATION.md). A new migration,
-- never editing V5, per this project's Flyway discipline (docs/architecture/ARCHITECTURE.md).

CREATE INDEX ix_ai_audit_records_ai_system_id ON ai_audit_records (ai_system_id);
