from __future__ import annotations

import argparse
from pathlib import Path

from evaluation.memory.report import write_report
from evaluation.memory.runner import EvaluationRunner
from evaluation.memory.schemas import PROFILE_NAMES


def _parse_profiles(parser: argparse.ArgumentParser, values: list[list[str]]) -> tuple[str, ...]:
    profiles = tuple(profile for value_group in values for value in value_group for profile in value.split(","))
    if not profiles or any(not profile for profile in profiles):
        parser.error("profiles must not contain empty comma-separated segments")
    if len(set(profiles)) != len(profiles):
        parser.error("profiles must not contain duplicates")
    if any(profile not in PROFILE_NAMES for profile in profiles):
        parser.error("profiles must be a finite subset of the supported profiles")
    return profiles


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the fixture-only memory evaluation suite")
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--profiles", action="append", nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    profiles = _parse_profiles(parser, args.profiles)
    report = EvaluationRunner.from_directory(args.cases).run(profiles)
    write_report(args.output, report)
    return 0 if report["gates"]["hybrid_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
