from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sqlite3
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "legacy-memory-baseline/v1"
REQUIRED_TABLES = (
    "interaction_events",
    "memory_audit_logs",
    "memory_candidates",
    "memory_relations",
    "semantic_memory_entries",
    "structured_memories",
)
IDENTIFIER_COLUMNS = {
    "interaction_events": "event_id",
    "memory_audit_logs": "audit_id",
    "memory_candidates": "candidate_id",
    "memory_relations": "relation_id",
    "semantic_memory_entries": "semantic_id",
    "structured_memories": "memory_id",
}
REQUIRED_COLUMNS = {
    "interaction_events": (
        "event_id", "user_id", "session_id", "event_type", "observed_at", "payload_json", "created_at"
    ),
    "memory_audit_logs": (
        "audit_id", "action", "reason", "memory_id", "metadata_json", "created_at"
    ),
    "memory_candidates": (
        "candidate_id", "source_event_id", "user_id", "memory_type", "status", "payload_json", "created_at"
    ),
    "memory_relations": (
        "relation_id", "from_id", "to_id", "relation_type", "payload_json", "created_at"
    ),
    "semantic_memory_entries": (
        "semantic_id", "memory_id", "status", "payload_json", "created_at"
    ),
    "structured_memories": (
        "memory_id", "user_id", "session_id", "memory_type", "status", "updated_at", "payload_json"
    ),
}
MEMORY_TYPE_TABLES = frozenset({"memory_candidates", "structured_memories"})
VALID_MEMORY_TYPES = frozenset(
    {
        "aviation_fact",
        "misconception",
        "policy",
        "preference",
        "prompt_asset_candidate",
        "reflection",
        "summary",
    }
)
PUBLIC_INVARIANT_PATHS = (
    "tests/fixtures/answer_contract_v1.json",
    "tests/integration/app_loop/test_answer_contract_compatibility.py",
    "tests/integration/answer_pipeline/test_memory_cannot_be_fact_source.py",
)
MINIMUM_HMAC_KEY_BYTES = 32


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _normalize_value(name: str, value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, bytes):
        return {"binary_base64": base64.b64encode(value).decode("ascii")}
    if not isinstance(value, str):
        return str(value)
    if name.endswith(("_at", "_timestamp")):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return value
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=UTC)
        return parsed.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")
    if name in {"metadata_json", "payload_json"}:
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


def _sqlite_uri(database: Path) -> str:
    return f"{database.resolve().as_uri()}?mode=ro&immutable=1"


def _read_schema(connection: sqlite3.Connection) -> dict[str, list[dict[str, Any]]]:
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'sqlite_sequence' ORDER BY name"
    ).fetchall()
    tables = tuple(row[0] for row in rows)
    if tables != REQUIRED_TABLES:
        raise ValueError(f"legacy database tables must be exactly {REQUIRED_TABLES}")

    schema: dict[str, list[dict[str, Any]]] = {}
    for table in tables:
        columns = connection.execute(f'PRAGMA table_info("{table}")').fetchall()
        names = tuple(column[1] for column in columns)
        if names != REQUIRED_COLUMNS[table]:
            raise ValueError(f"legacy table {table} columns do not match the frozen repository schema")
        schema[table] = [
            {
                "name": str(column[1]),
                "not_null": bool(column[3]),
                "primary_key_position": int(column[5]),
                "type": str(column[2]).upper(),
            }
            for column in columns
        ]
    return schema


def _table_rows(connection: sqlite3.Connection, table: str) -> list[dict[str, Any]]:
    cursor = connection.execute(f'SELECT * FROM "{table}"')
    names = tuple(description[0] for description in cursor.description)
    return [
        {name: _normalize_value(name, value) for name, value in zip(names, row, strict=True)}
        for row in cursor.fetchall()
    ]


def _row_hmac(key: bytes, table: str, row: Mapping[str, Any]) -> str:
    canonical = _canonical_json({"row": row, "table": table}).encode("utf-8")
    return hmac.new(key, canonical, hashlib.sha256).hexdigest()


def _normalized_rows(key: bytes, table: str, rows: Sequence[Mapping[str, Any]]) -> tuple[list[str], int, int]:
    identifier_name = IDENTIFIER_COLUMNS[table]
    prepared = [
        (
            "" if row.get(identifier_name) is None else str(row[identifier_name]),
            _canonical_json(row),
            _row_hmac(key, table, row),
            row,
        )
        for row in rows
    ]
    prepared.sort(key=lambda item: (item[0], item[1]))

    identifiers = [item[0] for item in prepared]
    duplicate_count = sum(
        1 for index, identifier in enumerate(identifiers) if identifier and identifier in identifiers[:index]
    )
    invalid_type_count = sum(
        1
        for _, _, _, row in prepared
        if table in MEMORY_TYPE_TABLES and row.get("memory_type") not in VALID_MEMORY_TYPES
    )
    return [item[2] for item in prepared], duplicate_count, invalid_type_count


def _source_reference_diagnostics(rows: Mapping[str, Sequence[Mapping[str, Any]]]) -> tuple[int, int]:
    event_ids = {str(row["event_id"]) for row in rows["interaction_events"]}
    memory_ids = {str(row["memory_id"]) for row in rows["structured_memories"]}
    missing = 0
    unknown = 0
    for row in rows["memory_candidates"]:
        source_event_id = row.get("source_event_id")
        if not isinstance(source_event_id, str) or not source_event_id.strip():
            missing += 1
        elif source_event_id not in event_ids:
            unknown += 1
    for row in rows["semantic_memory_entries"]:
        memory_id = row.get("memory_id")
        if not isinstance(memory_id, str) or not memory_id.strip():
            missing += 1
        elif memory_id not in memory_ids:
            unknown += 1
    for row in rows["memory_audit_logs"]:
        memory_id = row.get("memory_id")
        if memory_id is not None and (not isinstance(memory_id, str) or not memory_id.strip()):
            missing += 1
        elif isinstance(memory_id, str) and memory_id not in memory_ids:
            unknown += 1
    return missing, unknown


def _load_hmac_key(environment_name: str) -> bytes:
    if not environment_name:
        raise ValueError("--hmac-key-env must name an environment variable")
    raw = os.environ.get(environment_name)
    if raw is None:
        raise ValueError(f"required HMAC key environment variable is not set: {environment_name}")
    key = raw.encode("utf-8")
    if len(key) < MINIMUM_HMAC_KEY_BYTES or not raw.strip():
        raise ValueError("HMAC key must contain at least 32 non-empty UTF-8 bytes")
    return key


def _public_invariants(workspace: Path) -> list[dict[str, str]]:
    invariants: list[dict[str, str]] = []
    for relative in PUBLIC_INVARIANT_PATHS:
        path = workspace / relative
        if not path.is_file():
            raise ValueError(f"required public invariant fixture is missing: {relative}")
        invariants.append(
            {"path": relative.replace("\\", "/"), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        )
    return invariants


def freeze_baseline(database: Path, output: Path, hmac_key_env: str) -> dict[str, Any]:
    database = Path(database).resolve()
    output = Path(output).resolve()
    if database == output:
        raise ValueError("baseline output must not resolve to the source database")
    if not database.is_file():
        raise ValueError(f"legacy SQLite database does not exist: {database}")
    if output.exists():
        try:
            if os.path.samefile(database, output):
                raise ValueError("baseline output must not be a physical alias of the source database")
        except OSError as error:
            raise ValueError("cannot safely assess existing baseline output identity") from error
    key = _load_hmac_key(hmac_key_env)

    with sqlite3.connect(_sqlite_uri(database), uri=True) as connection:
        schema = _read_schema(connection)
        tables: dict[str, dict[str, Any]] = {}
        rows_by_table = {table: _table_rows(connection, table) for table in REQUIRED_TABLES}
        duplicate_count = 0
        invalid_type_count = 0
        for table in REQUIRED_TABLES:
            row_digests, duplicates, invalid_types = _normalized_rows(key, table, rows_by_table[table])
            tables[table] = {"count": len(row_digests), "row_hmac_sha256": row_digests}
            duplicate_count += duplicates
            invalid_type_count += invalid_types
        missing_source_count, unknown_source_count = _source_reference_diagnostics(rows_by_table)

    manifest = {
        "public_invariants": _public_invariants(Path(__file__).resolve().parents[1]),
        "schema_fingerprint": hashlib.sha256(_canonical_json(schema).encode("utf-8")).hexdigest(),
        "schema_version": SCHEMA_VERSION,
        "summary": {
            "audit_records": tables["memory_audit_logs"]["count"],
            "candidates": tables["memory_candidates"]["count"],
            "duplicate_id_count": duplicate_count,
            "events": tables["interaction_events"]["count"],
            "invalid_type_count": invalid_type_count,
            "memories": tables["structured_memories"]["count"],
            "missing_source_reference_count": missing_source_count,
            "relations": tables["memory_relations"]["count"],
            "semantic_entries": tables["semantic_memory_entries"]["count"],
            "unknown_source_reference_count": unknown_source_count,
        },
        "tables": tables,
    }
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(encoded)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Freeze a read-only legacy-memory baseline manifest.")
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--hmac-key-env", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        freeze_baseline(args.database, args.output, args.hmac_key_env)
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"baseline freeze failed: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
