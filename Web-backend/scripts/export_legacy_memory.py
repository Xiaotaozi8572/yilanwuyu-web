from __future__ import annotations

import argparse
import os
from pathlib import Path
from typing import Sequence

from src.memory.migration.bundle import export_bundle


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Export an authenticated, encrypted temporary legacy-memory bundle.")
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--subject-map", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--key-env", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        export_bundle(
            database=args.database,
            baseline=args.baseline,
            subject_map=args.subject_map,
            output=args.output,
            key_env=args.key_env,
        )
    except (OSError, ValueError) as error:
        print(f"legacy memory export failed: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
