from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from knowledge.config import load_rag_config
from knowledge.repository import KnowledgeRepository


class CliUsageError(ValueError):
    pass


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise CliUsageError(message)


def validate(config_path: str | Path) -> dict[str, object]:
    config = load_rag_config(config_path)
    repository = KnowledgeRepository.open_read_only(config.repository.path)
    try:
        return repository.validate_knowledge_base(
            expected_embedding_provider=config.embedding.provider,
            expected_embedding_dimension=config.embedding.dimension,
        )
    finally:
        repository.close()


def main(argv: list[str] | None = None) -> int:
    try:
        parser = JsonArgumentParser(description="Read-only knowledge-base validation.")
        parser.add_argument("--config", default="configs/rag.yaml")
        args = parser.parse_args(argv)
        report = validate(args.config)
        print(json.dumps(report, ensure_ascii=False))
        return 0 if report["status"] == "ok" else 1
    except Exception as exc:
        error = {
            "status": "error",
            "database": None,
            "checks": {},
            "error": {
                "code": (
                    "invalid_arguments"
                    if isinstance(exc, CliUsageError)
                    else "validation_failed"
                ),
                "message": str(exc),
            },
        }
        print(json.dumps(error, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
