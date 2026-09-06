from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from knowledge.retrieval_controller import (
    MaintainerAuthorizationError,
    RetrievalController,
    SourceParseError,
)
from knowledge.repository import DuplicateSourceError
from knowledge.schemas import ReviewStatus, SourceRecord


class CliUsageError(ValueError):
    pass


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise CliUsageError(message)


def _parser() -> argparse.ArgumentParser:
    parser = JsonArgumentParser(description="Ingest one audited knowledge source.")
    parser.add_argument("--input", required=True)
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--source-type", required=True)
    parser.add_argument("--authority-level", required=True)
    parser.add_argument(
        "--review-status",
        required=True,
        choices=[status.value for status in ReviewStatus],
    )
    parser.add_argument("--aircraft", required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--concept", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument(
        "--project-maintainer",
        action="store_true",
        help="Explicitly authorize reviewed ingestion as a project maintainer.",
    )
    return parser


def _error_payload(exc: Exception) -> dict[str, object]:
    if isinstance(exc, CliUsageError):
        code = "invalid_arguments"
    elif isinstance(exc, MaintainerAuthorizationError):
        code = "maintainer_authorization_required"
    elif isinstance(exc, DuplicateSourceError):
        code = "duplicate_source"
    elif isinstance(exc, (FileNotFoundError, UnicodeDecodeError, SourceParseError)):
        code = "input_parse_error"
    else:
        code = "ingestion_failed"
    return {
        "status": "error",
        "error": {"code": code, "message": str(exc)},
    }


def main(argv: list[str] | None = None) -> int:
    controller: RetrievalController | None = None
    try:
        args = _parser().parse_args(argv)
        review_status = ReviewStatus(args.review_status)
        controller = RetrievalController.from_config(args.config)
        parameters = {
            "input": args.input,
            "source_id": args.source_id,
            "title": args.title,
            "source_type": args.source_type,
            "authority_level": args.authority_level,
            "review_status": args.review_status,
            "aircraft": args.aircraft,
            "component": args.component,
            "concept": args.concept,
            "config": args.config,
            "project_maintainer": args.project_maintainer,
        }
        result = controller.ingest_source(
            input_path=Path(args.input),
            source_record=SourceRecord(
                source_id=args.source_id,
                title=args.title,
                source_type=args.source_type,
                authority_level=args.authority_level,
                review_status=review_status,
            ),
            aircraft=args.aircraft,
            component=args.component,
            concept=args.concept,
            maintainer_authorized=args.project_maintainer,
            cli_parameters=parameters,
        )
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps(_error_payload(exc), ensure_ascii=False), file=sys.stderr)
        return 2
    finally:
        if controller is not None:
            controller.close()


if __name__ == "__main__":
    raise SystemExit(main())
