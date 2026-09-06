from __future__ import annotations

import argparse
from collections.abc import Sequence
from pathlib import Path


VALID_STAGES = tuple(f"m{number}" for number in range(6))
PROTECTED_TOP_LEVEL = frozenset({".git", ".worktrees", ".venv"})
PERMANENT_ROOTS = (
    "contracts/memory",
    "services/memory-service",
    "src/memory/transport",
    "src/memory_worker",
    "src/observability",
    "src/evaluation/memory",
    "tests/contracts",
    "tests/unit/memory_worker",
    "tests/unit/observability",
    "tests/unit/evaluation/memory",
    "tests/integration/memory_service",
    "tests/integration/memory_async",
    "tests/integration/memory_privacy",
    "tests/integration/memory_migration",
    "tests/integration/memory_observability",
    "tests/integration/memory_evaluation",
    "tests/integration/memory_load",
    "tests/load/memory",
    "tests/faults/memory",
)
FORBIDDEN_SUFFIXES = frozenset({".pyc", ".tmp", ".bak", ".orig", ".rej"})
ROOT_SCRATCH_TOKENS = ("memory-new", "memory-final", "memory-copy")
SHARED_PERMANENT_ROOTS = frozenset({"src/observability"})


def collect_violations(workspace: Path, stage: str) -> list[Path]:
    if stage not in VALID_STAGES:
        raise ValueError(f"unsupported memory stage: {stage}")

    workspace = workspace.resolve()
    violations: list[Path] = []
    stage_root = workspace / "tmp" / "memory-system" / stage
    if stage_root.exists():
        violations.extend(path for path in stage_root.rglob("*") if path.is_file())

    source_root = workspace / "src"
    if source_root.exists():
        violations.extend(
            path
            for path in source_root.iterdir()
            if path.is_dir() and path.name.endswith(".egg-info")
        )

    for relative_root in PERMANENT_ROOTS:
        root = workspace / relative_root
        if not root.exists():
            continue
        shared_root = relative_root in SHARED_PERMANENT_ROOTS
        for path in root.rglob("*"):
            if any(part in PROTECTED_TOP_LEVEL for part in path.parts):
                continue
            if path.is_dir() and path.name == "__pycache__":
                if not shared_root:
                    violations.append(path)
            elif path.is_file() and path.suffix.lower() in FORBIDDEN_SUFFIXES:
                if not shared_root or "memory" in path.name.lower():
                    violations.append(path)

    for path in workspace.iterdir():
        lowered = path.stem.lower()
        if path.is_file() and (
            path.suffix.lower() in FORBIDDEN_SUFFIXES
            or any(token in lowered for token in ROOT_SCRATCH_TOKENS)
        ):
            violations.append(path)

    target = workspace / "services" / "memory-service" / "target"
    if target.exists():
        violations.append(target)
    return sorted(set(violations))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Report memory-system workspace residue without deleting it."
    )
    parser.add_argument("--stage", required=True, choices=VALID_STAGES)
    parser.add_argument("--check", required=True, action="store_true")
    return parser


def main(
    argv: Sequence[str] | None = None, *, workspace: Path | None = None
) -> int:
    args = _parser().parse_args(argv)
    checked_workspace = (
        workspace.resolve()
        if workspace is not None
        else Path(__file__).resolve().parents[1]
    )
    violations = collect_violations(checked_workspace, args.stage)
    if not violations:
        print(f"memory workspace clean for stage {args.stage}")
        return 0

    print(f"memory workspace violations for stage {args.stage}:")
    for path in violations:
        print(path.relative_to(checked_workspace))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
