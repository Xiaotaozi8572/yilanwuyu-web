# M5 Frozen Legacy and Public-Contract Baseline

Date: 2026-07-23

The frozen SQLite sample uses the actual legacy `SQLiteMemoryRepository` business
schema: `interaction_events`, `memory_candidates`, `structured_memories`,
`memory_relations`, `semantic_memory_entries`, and `memory_audit_logs`. It is
opened only through SQLite's read-only immutable URI. The resulting
`legacy-memory-baseline/v1` manifest contains a schema fingerprint, table counts,
row HMAC-SHA-256 values, aggregate invalid-type and source-reference diagnostics,
and SHA-256 references to pre-existing public-contract regression fixtures. It
contains no legacy row fields, payloads, user/session identifiers, answer text,
or runtime HMAC material.

Frozen artifact digests:

- `tests/fixtures/memory_migration/legacy_sample.sqlite3`: `e977acca930320d0d740a26dd9d0df946a999542a6e54ab052c5d649a0e714f9`
- `tests/fixtures/memory_migration/legacy_baseline.json`: `f422cf934c85e2f02e45ccc490a0a66368bc6814b070f8227267390961b2b494`

Validated command set:

- `python -m pytest -p no:cacheprovider tests/integration/memory_migration/test_legacy_baseline.py tests/integration/app_loop/test_answer_contract_compatibility.py tests/integration/answer_pipeline/test_memory_cannot_be_fact_source.py -q` — 9 passed.
- `python scripts/freeze_memory_baseline.py --database tests/fixtures/memory_migration/legacy_sample.sqlite3 --output tests/fixtures/memory_migration/legacy_baseline.json --hmac-key-env MEMORY_MIGRATION_TEST_HMAC_KEY` — exit 0 with a test-only runtime environment value.

The baseline test verifies byte-equivalent independent runs, exact legacy
repository-schema compatibility, immutable legacy-file contents and inode
identity, fail-closed missing/weak runtime HMAC handling, and digest-only public
invariant entries. It also verifies that an output path resolving to the frozen
SQLite source is rejected before any source read or output write.
An existing hard-link alias of the source is likewise rejected by physical
same-file identity before the source is opened or the output is written.
A Windows directory-junction alias is also covered as a runnable substitute for
file symbolic links when the test process lacks symbolic-link privileges. The
junction test uses the fixed local Windows command interpreter and proves the
SQLite connection boundary is not invoked on alias rejection.
