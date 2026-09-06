from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from knowledge.retrieval_controller import RetrievalController


class CliUsageError(ValueError):
    pass


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise CliUsageError(message)


def main(argv: list[str] | None = None) -> int:
    controller: RetrievalController | None = None
    try:
        parser = JsonArgumentParser(description="Atomically rebuild knowledge indexes.")
        parser.add_argument("--config", default="configs/rag.yaml")
        args = parser.parse_args(argv)
        controller = RetrievalController.from_config(args.config)
        result = controller.rebuild_indexes()
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:
        error = {
            "status": "error",
            "error": {
                "code": (
                    "invalid_arguments"
                    if isinstance(exc, CliUsageError)
                    else "index_rebuild_failed"
                ),
                "message": str(exc),
            },
        }
        print(json.dumps(error, ensure_ascii=False), file=sys.stderr)
        return 2
    finally:
        if controller is not None:
            controller.close()


if __name__ == "__main__":
    raise SystemExit(main())
