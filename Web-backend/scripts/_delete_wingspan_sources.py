"""一次性数据修复：级联删除含 35.4 错误翼展数据的 source（供重新 ingest 覆盖）。

仅删普通表；text_chunks_fts 与 vector_embeddings_ann 在下次 ingest 时由
_replace_staged_index_rows / _sync_ann_index_rows 全量重建，无需手动处理。
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DB_PATH = PROJECT_ROOT / "data" / "processed" / "knowledge.sqlite3"


def main() -> int:
    conn = sqlite3.connect(str(DB_PATH))
    cur = conn.cursor()
    try:
        # 1. 找出所有含 35.4 的 source_id（以实际数据为准，而非硬编码）
        rows = cur.execute(
            "SELECT DISTINCT source_id FROM text_chunks WHERE text LIKE '%35.4%'"
        ).fetchall()
        source_ids = [r[0] for r in rows]
        print(f"待删除 source（含 35.4）: {len(source_ids)} 个")
        for sid in source_ids:
            print(f"  - {sid}")

        if not source_ids:
            print("无含 35.4 的 source，无需删除")
            return 0

        # 2. 收集这些 source 的所有 chunk_id
        placeholders = ",".join("?" for _ in source_ids)
        chunk_rows = cur.execute(
            f"SELECT chunk_id FROM text_chunks WHERE source_id IN ({placeholders})",
            source_ids,
        ).fetchall()
        chunk_ids = [r[0] for r in chunk_rows]
        print(f"\n关联 chunk 数: {len(chunk_ids)}")

        with conn:
            # 3. 级联删除（先子表后父表）
            for table, column, ids in [
                ("scene_object_chunks", "chunk_id", chunk_ids),
                ("text_chunks", "source_id", source_ids),
                ("parent_documents", "source_id", source_ids),
                ("scene_object_sources", "source_id", source_ids),
                ("graph_node_sources", "source_id", source_ids),
                ("ingestion_jobs", "source_id", source_ids),
                ("vector_embeddings", "source_id", source_ids),
                ("knowledge_sources", "source_id", source_ids),
            ]:
                if not ids:
                    continue
                ph = ",".join("?" for _ in ids)
                cur.execute(f"DELETE FROM {table} WHERE {column} IN ({ph})", ids)
                print(f"  DELETE {table} ({column}) -> {cur.rowcount} 行")

        # 4. 校验残留
        leftover = cur.execute(
            "SELECT COUNT(*) FROM text_chunks WHERE text LIKE '%35.4%'"
        ).fetchone()[0]
        print(f"\n删除后 text_chunks 含 35.4 残留: {leftover}")
        remaining_sources = cur.execute("SELECT COUNT(*) FROM knowledge_sources").fetchone()[0]
        print(f"剩余 source 数: {remaining_sources}")
        return 0 if leftover == 0 else 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
