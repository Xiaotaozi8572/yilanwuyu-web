"""One-shot verification: confirm rebuild produced real bge_m3 vectors."""
from __future__ import annotations

import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[1] / "data" / "processed" / "knowledge.sqlite3"


def main() -> int:
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    try:
        print("=== vector_index_metadata ===")
        for row in conn.execute("SELECT * FROM vector_index_metadata"):
            print(dict(row))

        print("\n=== vector_embeddings is_mock distribution ===")
        for row in conn.execute("SELECT is_mock, COUNT(*) AS cnt FROM vector_embeddings GROUP BY is_mock"):
            print(dict(row))

        print("\n=== active index_versions ===")
        for row in conn.execute(
            "SELECT index_name, version, status, updated_at "
            "FROM index_versions WHERE status = 'active'"
        ):
            print(dict(row))

        print("\n=== sample vector (first 5 dims) ===")
        row = conn.execute(
            "SELECT chunk_id, provider, model, dimension, is_mock, "
            "substr(vector_json, 1, 120) AS vector_preview FROM vector_embeddings LIMIT 1"
        ).fetchone()
        if row:
            print(dict(row))

        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
