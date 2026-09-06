"""Generate deterministic Python bindings for the versioned memory contracts."""

from __future__ import annotations

import argparse
import re
import tempfile
from pathlib import Path

from grpc_tools import protoc


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts" / "memory" / "v1"
OUTPUT = ROOT / "src" / "memory" / "transport" / "generated"
TASK_TEMP = ROOT / "tmp" / "memory-system" / "m0"
GENERATED_PATTERN = re.compile(r"^memory_.+_pb2(?:_grpc)?\.py$")
SIBLING_IMPORT = re.compile(
    rb"(?m)^import (memory_[A-Za-z0-9_]+_pb2) as ([A-Za-z0-9_]+)$"
)


def _proto_files() -> list[Path]:
    files = sorted(SOURCE.glob("*.proto"), key=lambda path: path.name)
    if not files:
        raise SystemExit(f"no Proto sources found under {SOURCE}")
    return files


def _normalize_sibling_imports(directory: Path) -> None:
    for path in sorted(directory.glob("*_pb2*.py"), key=lambda item: item.name):
        content = path.read_bytes()
        normalized = SIBLING_IMPORT.sub(rb"from . import \1 as \2", content)
        if normalized != content:
            path.write_bytes(normalized)


def _generate_to(directory: Path) -> list[Path]:
    directory.mkdir(parents=True, exist_ok=True)
    sources = _proto_files()
    result = protoc.main(
        [
            "grpc_tools.protoc",
            f"-I{SOURCE}",
            f"--python_out={directory}",
            f"--grpc_python_out={directory}",
            *(path.name for path in sources),
        ]
    )
    if result != 0:
        raise SystemExit(result)
    _normalize_sibling_imports(directory)
    return sorted(
        (
            path
            for path in directory.iterdir()
            if path.is_file() and GENERATED_PATTERN.fullmatch(path.name)
        ),
        key=lambda path: path.name,
    )


def generate() -> None:
    _generate_to(OUTPUT)


def check() -> None:
    TASK_TEMP.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix="memory-contract-check-", dir=TASK_TEMP
    ) as temporary:
        temporary_output = Path(temporary)
        expected = _generate_to(temporary_output)
        expected_names = {path.name for path in expected}
        actual = sorted(
            (
                path
                for path in OUTPUT.glob("*_pb2*.py")
                if GENERATED_PATTERN.fullmatch(path.name)
            ),
            key=lambda path: path.name,
        )
        actual_names = {path.name for path in actual}
        if actual_names != expected_names:
            missing = sorted(expected_names - actual_names)
            stale = sorted(actual_names - expected_names)
            raise SystemExit(
                f"generated memory contracts are stale: missing={missing}, stale={stale}"
            )
        changed = [
            path.name
            for path in expected
            if path.read_bytes() != (OUTPUT / path.name).read_bytes()
        ]
        if changed:
            raise SystemExit(
                "generated memory contracts differ: " + ", ".join(changed)
            )
    print("generated memory contracts are current")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="regenerate in task-local temporary storage and compare bytes",
    )
    args = parser.parse_args()
    if args.check:
        check()
    else:
        generate()


if __name__ == "__main__":
    main()
