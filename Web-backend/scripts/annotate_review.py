#!/usr/bin/env python3
"""annotate_review.py — 抽检结论回写辅助脚本（T19：知识库人工抽检与审核留痕）。

用途
----
人工完成知识库抽检后，将某条 source 的 review_status 从当前值回写为目标值。
典型场景：抽检判定"降级"的条目从 reviewed 回写为 candidate。

约束
----
- 仅 Python 标准库，无第三方依赖。
- 数据为 SQLite 运行库，走既有入库接口 KnowledgeRepository
  （register_source upsert / deprecate_source），不直接手改 SQLite。
- 打印操作前/后状态（JSON 到 stdout，对齐 scripts/ingest_sources.py 约定）。

用法
----
    python scripts/annotate_review.py --source-id c919-science-xxx-v1 --status candidate
    python scripts/annotate_review.py --source-id c919-science-xxx-v1 --status candidate --dry-run
    python scripts/annotate_review.py --source-id c919-science-xxx-v1 --status deprecated

说明
----
- --status 取值：draft / candidate / reviewed / deprecated（与 ReviewStatus 枚举一致）。
- 仅回写 knowledge_sources 表（source 级）；text_chunks 级 review_status 不在本脚本范围
  （T19 只要求 source 条目状态回写；检索侧 source 级过滤随回写立即生效）。
- manifest 治理清单（data/knowledge_sources/**/manifest.yaml）当前全部为 candidate，
  回写后与运行库一致；若未来清单状态不同，需人工同步（本脚本受"仅标准库"约束，
  不直接改 YAML，也不依赖 PyYAML）。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from knowledge.repository import KnowledgeRepository
from knowledge.schemas import ReviewStatus, SourceRecord


class CliUsageError(ValueError):
    pass


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise CliUsageError(message)


def _parser() -> argparse.ArgumentParser:
    parser = JsonArgumentParser(
        description="Rewrite review_status of one knowledge source via repository interface."
    )
    parser.add_argument(
        "--db",
        default=str(PROJECT_ROOT / "data" / "processed" / "knowledge.sqlite3"),
        help="Path to knowledge SQLite database (default: data/processed/knowledge.sqlite3).",
    )
    parser.add_argument("--source-id", required=True, help="Source to annotate.")
    parser.add_argument(
        "--status",
        required=True,
        choices=[status.value for status in ReviewStatus],
        help="Target review_status.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the planned change without writing.",
    )
    return parser


def _error_payload(exc: Exception) -> dict[str, object]:
    if isinstance(exc, CliUsageError):
        code = "invalid_arguments"
    elif isinstance(exc, KeyError):
        code = "source_not_found"
    else:
        code = "annotation_failed"
    return {
        "status": "error",
        "error": {"code": code, "message": str(exc)},
    }


def _summary(
    *,
    source_id: str,
    before: str,
    after: str,
    dry_run: bool,
) -> dict[str, object]:
    return {
        "status": "dry_run" if dry_run else "ok",
        "source_id": source_id,
        "before": before,
        "after": after,
        "written": not dry_run,
    }


def main(argv: list[str] | None = None) -> int:
    repo: KnowledgeRepository | None = None
    try:
        args = _parser().parse_args(argv)
        target = ReviewStatus(args.status)
        repo = KnowledgeRepository(Path(args.db))
        source = repo.get_source(args.source_id)
        if source is None:
            raise KeyError(args.source_id)
        before = source.review_status.value
        if args.dry_run:
            print(json.dumps(_summary(
                source_id=args.source_id, before=before,
                after=target.value, dry_run=True,
            ), ensure_ascii=False))
            return 0
        if target is ReviewStatus.DEPRECATED:
            repo.deprecate_source(args.source_id)
        else:
            record = SourceRecord(
                source_id=source.source_id,
                source_type=source.source_type,
                title=source.title,
                authority_level=source.authority_level,
                review_status=target,
                version=source.version,
                created_at=source.created_at,
                updated_at=source.updated_at,
                content_hash=source.content_hash,
                original_uri=source.original_uri,
                applicable_aircraft=source.applicable_aircraft,
                metadata=source.metadata,
            )
            repo.register_source(record)
        after = repo.get_source(args.source_id).review_status.value
        print(json.dumps(_summary(
            source_id=args.source_id, before=before, after=after, dry_run=False,
        ), ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps(_error_payload(exc), ensure_ascii=False), file=sys.stderr)
        return 2
    finally:
        if repo is not None:
            repo.close()


if __name__ == "__main__":
    raise SystemExit(main())
