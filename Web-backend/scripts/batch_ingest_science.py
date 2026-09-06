"""Batch ingest 01_science sources as reviewed with project-maintainer authorization.

Reads all 6 sub-partition manifests under data/knowledge_sources/<aircraft>/01_science/
for each selected aircraft (default: c919), extracts source metadata, and ingests each
source file with review_status=reviewed.

Progress is shown as a live bar per aircraft; after all aircraft finish, a build
summary (chunking rules / embedding model / index method) is printed from the actual
runtime config, provider, and the recorded vector_index_metadata.

Examples:
    python scripts/batch_ingest_science.py
    python scripts/batch_ingest_science.py --aircraft j20 y20 z20
    python scripts/batch_ingest_science.py --aircraft all
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from core.simple_yaml import parse_simple_yaml
from knowledge.repository import DuplicateSourceError
from knowledge.retrieval_controller import RetrievalController
from knowledge.schemas import ReviewStatus, SourceRecord

KNOWLEDGE_ROOT = PROJECT_ROOT / "data" / "knowledge_sources"
MANIFEST_PATH = KNOWLEDGE_ROOT / "MANIFEST.yaml"
# Absolute path string: load_rag_config accepts str|Path, and `parameters["config"]` is
# JSON-serialized into the ingestion audit record (a Path would break json.dumps).
CONFIG_PATH = str(PROJECT_ROOT / "configs" / "rag.yaml")

SUB_PARTITIONS = [
    "01_overview",
    "02_structure_systems",
    "03_principles",
    "04_history_milestones",
    "05_applications_value",
    "06_comparison",
]

# Default component/concept fallbacks when manifest lacks the field.
# These are keyed by sub-partition (aircraft-agnostic) and apply to all objects.
COMPONENT_DEFAULTS = {
    "01_overview": "overall",
    "02_structure_systems": "structure",
    "03_principles": "principle",
    "04_history_milestones": "history",
    "05_applications_value": "application",
    "06_comparison": "comparison",
}
CONCEPT_DEFAULTS = {
    "01_overview": "overview",
    "02_structure_systems": "structure_system",
    "03_principles": "principle",
    "04_history_milestones": "milestone",
    "05_applications_value": "application_value",
    "06_comparison": "comparison",
}

_BAR_WIDTH = 40
# Live \r bars only make sense on an interactive terminal; when stdout is piped to a
# file, fall back to one plain progress line per source.
_IS_TTY = sys.stdout.isatty()

# R0/T4-3.6: manifest knowledge_type -> internal chunk knowledge_type.
# Only "parameter_fact" sources are promoted to structured "parameter" chunks
# (with value/unit metadata); everything else keeps the historical "explanation"
# default so the 300+ non-parameter sources are unaffected.
_MANIFEST_TO_CHUNK_KNOWLEDGE_TYPE = {
    "parameter_fact": "parameter",
}


def _chunk_knowledge_type(manifest_type: str) -> str:
    return _MANIFEST_TO_CHUNK_KNOWLEDGE_TYPE.get(manifest_type, "explanation")


def load_manifest(manifest_path: Path) -> dict:
    text = manifest_path.read_text(encoding="utf-8")
    return parse_simple_yaml(text)


def _active_aircraft() -> list[str]:
    """Return active object ids with the 01_science partition enabled, in manifest order."""
    data = load_manifest(MANIFEST_PATH)
    objects = data.get("knowledge_base", {}).get("active_objects", [])
    return [
        obj.get("id", "")
        for obj in objects
        if isinstance(obj, dict)
        and obj.get("id")
        and obj.get("partition_enable", {}).get("01_science") is True
    ]


def resolve_aircraft(requested: list[str]) -> list[str]:
    """Validate --aircraft choices against MANIFEST.yaml and expand 'all'."""
    active = _active_aircraft()
    if not active:
        raise SystemExit(f"[ERROR] no active 01_science objects in {MANIFEST_PATH}")
    if "all" in requested:
        return active
    unknown = [name for name in requested if name not in active]
    if unknown:
        raise SystemExit(
            f"[ERROR] unknown aircraft: {', '.join(unknown)}; "
            f"valid choices are: {', '.join(active)} (or 'all')"
        )
    return list(dict.fromkeys(requested))


def collect_sources(aircraft: str) -> list[dict]:
    """Collect all source entries from 6 sub-partition manifests for one aircraft."""
    science_base = KNOWLEDGE_ROOT / aircraft / "01_science"
    all_sources: list[dict] = []
    for sub in SUB_PARTITIONS:
        manifest_path = science_base / sub / "manifest.yaml"
        if not manifest_path.exists():
            print(f"[WARN] manifest not found: {manifest_path}", file=sys.stderr)
            continue
        data = load_manifest(manifest_path)
        sources = data.get("sources", [])
        for src in sources:
            files = src.get("files", [])
            if not files:
                continue
            file_path = files[0].get("path", "")
            if not file_path:
                continue
            full_path = science_base / sub / file_path
            if not full_path.exists():
                print(f"[WARN] file not found: {full_path}", file=sys.stderr)
                continue
            all_sources.append({
                "sub_partition": sub,
                "source_id": src.get("source_id", ""),
                "source_type": src.get("source_type", "text"),
                "title": src.get("title", ""),
                "authority_level": src.get("authority_level", "standard"),
                "aircraft": src.get("aircraft", aircraft),
                "component": src.get("component", COMPONENT_DEFAULTS.get(sub, "overall")),
                "concept": src.get("concept", CONCEPT_DEFAULTS.get(sub, "overview")),
                "knowledge_type": src.get("knowledge_type", ""),
                "full_path": str(full_path),
            })
    return all_sources


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Batch ingest 01_science sources as reviewed for one or more aircraft.",
    )
    parser.add_argument(
        "--aircraft",
        nargs="+",
        default=["c919"],
        help="知识对象 id，可多选；传 'all' 遍历 MANIFEST.yaml 中启用 01_science 的全部对象",
    )
    return parser


def _progress_bar(current: int, total: int, width: int = 40) -> str:
    if total <= 0:
        return "-" * width
    ratio = max(0.0, min(1.0, current / total))
    filled = int(width * ratio)
    if current > 0 and filled < 1:
        filled = 1
    return "#" * filled + "-" * (width - filled)


def _write_progress(aircraft: str, current: int, total: int, *, suffix: str = "") -> None:
    """Write one progress position: live \r line on a TTY, plain line otherwise."""
    if total <= 0:
        return
    pct = current / total * 100.0
    bar = _progress_bar(current, total)
    line = f"[{aircraft}] {bar} {current:>3}/{total} ({pct:5.1f}%) {suffix}"
    if _IS_TTY:
        sys.stdout.write(f"\r{line}")
    else:
        sys.stdout.write(f"{line}\n")
    sys.stdout.flush()


def _finish_progress_line() -> None:
    """End the current \r progress line on a TTY before printing a normal line."""
    if _IS_TTY:
        sys.stdout.write("\n")
        sys.stdout.flush()


def _recorded_vector_metadata(repository_path: Path) -> dict[str, object] | None:
    """Read the ground-truth vector_index_metadata row recorded by the last index build."""
    try:
        conn = sqlite3.connect(str(repository_path))
        try:
            row = conn.execute(
                "SELECT provider, model, dimension, is_mock, updated_at "
                "FROM vector_index_metadata WHERE index_name = 'dense_chunks'"
            ).fetchone()
        finally:
            conn.close()
        if row is None:
            return None
        return {
            "provider": row[0],
            "model": row[1],
            "dimension": row[2],
            "is_mock": int(row[3]),
            "updated_at": row[4],
        }
    except sqlite3.Error:
        return None


def print_build_summary(controller: RetrievalController) -> None:
    """Print chunking / embedding / index facts drawn from the actual runtime state."""
    config = controller.config
    chunking = config.chunking
    provider = getattr(controller.embedding_provider, "provider", config.embedding.provider)
    model = getattr(controller.embedding_provider, "_model", None) or getattr(controller.embedding_provider, "model", config.embedding.provider)
    provider_is_mock = bool(getattr(controller.embedding_provider, "is_mock", False))
    recorded = _recorded_vector_metadata(config.repository.path)

    enabled_channels = [
        name for name, channel in config.retrieval.channels.items() if channel.enabled
    ]

    print()
    print("=" * 60)
    print("构建摘要（取自实际运行状态）")
    print("=" * 60)
    print(
        f"[切块规则] min_chars={chunking.min_chars} target_chars={chunking.target_chars} "
        f"max_chars={chunking.max_chars} overlap_chars={chunking.overlap_chars}"
    )
    print(f"[向量化模型] {provider} / {model} / {config.embedding.dimension} 维")
    if recorded is not None:
        recorded_state = "真实 embedding" if not recorded["is_mock"] else "字符哈希兜底(mock)"
        print(
            f"[索引实况] vector_index_metadata: provider={recorded['provider']} "
            f"model={recorded['model']} dimension={recorded['dimension']} "
            f"is_mock={recorded['is_mock']} ({recorded_state}) @ {recorded['updated_at']}"
        )
        if recorded["is_mock"]:
            print(
                f"[警告] is_mock=1：{provider} API key 未配置或请求失败，实际向量为字符哈希兜底，"
                "请配置密钥后运行 scripts/rebuild_knowledge_indexes.py 重建索引"
            )
    else:
        print(f"[索引实况] 尚未生成 vector_index_metadata（{config.repository.path}）")
    if provider_is_mock and (recorded is None or not recorded["is_mock"]):
        print("[警告] 当前 embedding provider 处于 mock 兜底状态，本次写入的向量非语义向量")
    print(f"[检索通道] {'、'.join(enabled_channels)}")
    print(
        f"[索引方法] keyword=BM25(SQLite FTS5 text_chunks_fts) | "
        f"dense=sqlite-vec vec0 cosine ANN (vector_index={config.embedding.vector_index})"
    )
    print(
        "           每次入库/重建收集全部 reviewed 块，整体重新向量化后经 index_versions 原子换入"
    )
    print("=" * 60)


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    aircraft_list = resolve_aircraft(args.aircraft)

    controller = RetrievalController.from_config(CONFIG_PATH)
    total_count = 0
    success_count = 0
    skipped_count = 0
    failure_count = 0
    failures: list[dict] = []

    try:
        for aircraft in aircraft_list:
            sources = collect_sources(aircraft)
            if not sources:
                print(f"[{aircraft}] No sources found to ingest.", file=sys.stderr)
                continue

            print(f"[{aircraft}] Found {len(sources)} sources to ingest as reviewed.")
            total_count += len(sources)
            aircraft_success = 0
            aircraft_skipped = 0
            aircraft_failure = 0

            for idx, src in enumerate(sources, 1):
                source_id = src["source_id"]
                _write_progress(
                    aircraft, idx - 1, len(sources), suffix=f"processing {source_id}"
                )

                parameters = {
                    "input": src["full_path"],
                    "source_id": source_id,
                    "title": src["title"],
                    "source_type": src["source_type"],
                    "authority_level": src["authority_level"],
                    "review_status": "reviewed",
                    "aircraft": src["aircraft"],
                    "component": src["component"],
                    "concept": src["concept"],
                    "config": CONFIG_PATH,
                    "project_maintainer": True,
                }

                try:
                    result = controller.ingest_source(
                        input_path=Path(src["full_path"]),
                        source_record=SourceRecord(
                            source_id=source_id,
                            title=src["title"],
                            source_type=src["source_type"],
                            authority_level=src["authority_level"],
                            review_status=ReviewStatus.REVIEWED,
                        ),
                        aircraft=src["aircraft"],
                        component=src["component"],
                        concept=src["concept"],
                        maintainer_authorized=True,
                        cli_parameters=parameters,
                        knowledge_type=_chunk_knowledge_type(src["knowledge_type"]),
                    )
                    status = result.get("status", "unknown")
                    if status == "success" or "source_id" in result:
                        aircraft_success += 1
                    else:
                        aircraft_failure += 1
                        failures.append({"source_id": source_id, "result": result})
                        _finish_progress_line()
                        print(f"  -> UNEXPECTED: {json.dumps(result, ensure_ascii=False)[:200]}")
                except DuplicateSourceError as exc:
                    aircraft_skipped += 1
                except Exception as exc:
                    aircraft_failure += 1
                    failures.append({"source_id": source_id, "error": str(exc)})
                    _finish_progress_line()
                    print(f"  -> FAILED: {exc}")

                _write_progress(
                    aircraft, idx, len(sources),
                    suffix=f"{source_id}: ok={aircraft_success} skip={aircraft_skipped} fail={aircraft_failure}",
                )

                # Small delay to avoid embedding API rate limits.
                if idx % 10 == 0:
                    time.sleep(1.0)

            _finish_progress_line()
            success_count += aircraft_success
            skipped_count += aircraft_skipped
            failure_count += aircraft_failure
            print(
                f"[{aircraft}] Ingested: {len(sources)} "
                f"| Success: {aircraft_success} | Skipped: {aircraft_skipped} | Failed: {aircraft_failure}"
            )

    finally:
        controller.close()

    print("=" * 60)
    print(
        f"Total: {total_count} "
        f"| Success: {success_count} | Skipped: {skipped_count} | Failed: {failure_count}"
    )

    print_build_summary(controller)

    if failures:
        print("\nFailures detail:")
        for f in failures[:10]:
            print(f"  - {f['source_id']}: {str(f.get('error') or f.get('result'))[:150]}")

    return 0 if failure_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
