"""One-shot maintenance: reset vector_index_metadata.is_mock to unblock rebuild.

The initial bulk ingestion silently fell back to character-hash mock vectors
(is_mock=1) even though the provider was configured as bge_m3. After fixing
BGE_M3_Provider to load the local model directly, the provider now reports
is_mock=False, which mismatches the persisted metadata and blocks
SQLiteVectorStore construction (and therefore rebuild_knowledge_indexes.py).

This script resets the metadata row so the controller can be constructed.
rebuild_knowledge_indexes.py will then re-embed all reviewed chunks with real
BGE-M3 vectors and atomically replace both the metadata and the vectors.
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[1] / "data" / "processed" / "knowledge.sqlite3"


def main() -> int:
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    try:
        before = conn.execute(
            "SELECT index_name, provider, model, dimension, is_mock, index_version, updated_at "
            "FROM vector_index_metadata WHERE index_name = 'dense_chunks'"
        ).fetchone()
        if before is None:
            print("[reset] no dense_chunks metadata row found; nothing to reset")
            return 0
        print("[reset] BEFORE:", dict(before))

        conn.execute(
            "UPDATE vector_index_metadata SET is_mock = 0 "
            "WHERE index_name = 'dense_chunks'"
        )
        conn.commit()

        after = conn.execute(
            "SELECT index_name, provider, model, dimension, is_mock, index_version, updated_at "
            "FROM vector_index_metadata WHERE index_name = 'dense_chunks'"
        ).fetchone()
        print("[reset] AFTER: ", dict(after))
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
