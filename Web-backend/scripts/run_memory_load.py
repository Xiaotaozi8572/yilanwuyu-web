"""Fail-closed M5-7 local loopback load driver.

The public entry point invokes only the approved Maven Wrapper JUnit target.
The private entry point is marker-gated and exists solely for that harness.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import stat
import subprocess
import sys
import time
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

import grpc
from google.protobuf import empty_pb2

from app.api.schemas import TextQueryRequest
from core.contracts import MemoryContext
from core.runtime_settings import MemorySettings, RuntimeSettings
from core.settings import load_settings
from knowledge.ingestion.text_ingestor import TextIngestor
from knowledge.retrieval_controller import RetrievalController
from knowledge.schemas import ReviewStatus, SourceRecord
from memory.controller import MemoryController
from memory.port import MemoryReadResult
from memory.repository import SQLiteMemoryRepository
from memory.schemas import InteractionEvent, MemoryRetrievalRequest, PrivacyLevel
from memory.transport.generated import (
    memory_context_pb2_grpc,
    memory_event_pb2_grpc,
)
from memory_worker.server import FakeWorkerInterferenceBarrier, build_fake_server
from security.session_identity import (
    LocalDemoSessionAssertionVerifier,
    trusted_identity_scope,
)
from services.app_pipeline import AppPipeline
from tests.helpers.knowledge_seed import seed_controller_chunks
from tests.load.memory.assertions import (
    assert_canonical_report_gates,
    assert_profile_gates,
    freeze_worker_on_allowance_ms,
)
from tests.load.memory.loopback_port import LoopbackLoadMemoryPort
from tests.load.memory.scenario import LoadSchedule, ScheduledOperation


PROTOCOL_SCHEMA_VERSION = "m5-load-loopback/v1"
READY_FILE_NAME = "ready.json"
DONE_FILE_NAME = "done.json"
CLIENT_DONE_FILE_NAME = "client-done.json"
CORRELATION_FAILURE_FILE_NAME = "correlation-failure.json"
WORKER_READY_FILE_NAME = "worker-ready.json"
WORKER_ACTIVE_FILE_NAME = "worker-active.json"
HARNESS_MARKER_ENV = "YILAN_M5_LOAD_HARNESS"
HARNESS_MARKER_VALUE = "M5LoadHarnessTest/v1"
TEST_HOOK_ENV = "YILAN_M5_LOAD_TEST_HOOK"
TEST_HOOK_VALUE = "allow-short-smoke/v1"

_CANONICAL_DIMENSIONS = (50, 20, 50, 600)
_JAVA_PROFILES = frozenset(("java-postgres-only", "full"))
_OUTER_PROFILES = _JAVA_PROFILES
_NONCE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{32,128}$")
_RUN_NAME_PATTERN = re.compile(r"^run-[A-Za-z0-9_-]{3,96}$")
_READY_FIELDS = frozenset(
    (
        "schema_version",
        "status",
        "nonce",
        "profile",
        "endpoint",
        "sessions",
        "read_rate",
        "event_rate",
        "duration_seconds",
    )
)
_DONE_FIELDS = frozenset(
    (
        "schema_version",
        "status",
        "nonce",
        "profile",
        "worker_off_p95_ms",
        "report",
    )
)
_CLIENT_DONE_FIELDS = frozenset(
    ("schema_version", "status", "nonce", "profile", "result")
)
_CLIENT_RESULT_FIELDS = frozenset(
    (
        "counts",
        "python_loopback_wait_samples_ns",
        "test_pipeline_answer_samples_ns",
    )
)
_CORRELATION_FAILURE_FIELDS = frozenset(
    ("schema_version", "status", "nonce", "profile", "correlation_digest")
)
_WORKER_ACTIVE_FIELDS = frozenset(
    ("schema_version", "status", "nonce", "profile")
)
_REPORT_FIELDS = frozenset(
    (
        "profile",
        "counts",
        "java_resolve_service_ms",
        "python_loopback_grpc_wait_ms",
        "test_pipeline_answer_p95_ms",
        "critical_counters",
        "resources",
    )
)
_OUTER_REPORT_FIELDS = frozenset(
    (
        "measurement_scope",
        "answer_evidence_regression",
        "worker_on_allowance_ms",
        "profiles",
        "gates",
        "resource_availability",
    )
)
_MEASUREMENT_SCOPE_FIELDS = frozenset(("java_memory_path", "answer_evidence"))
_OUTER_GATE_FIELDS = frozenset(("profile_gates_passed", "canonical_600_second_run"))
_RESOURCE_FIELDS = frozenset(
    ("cpu_time_ms", "java_working_set_bytes", "gpu_vram_bytes", "gpu_reason")
)
_MEASUREMENT_SCOPE = {
    "java_memory_path": "raw_generated_stub_loopback",
    "answer_evidence": "test_only_pipeline_probe",
}
_ANSWER_EVIDENCE_REGRESSION = "separate"
_GPU_UNAVAILABLE_REASON = "unavailable"
_RAW_REPORT_KEYS = frozenset(
    (
        "query",
        "query_text",
        "subject",
        "session",
        "session_id",
        "token",
        "payload",
        "traceparent",
        "source",
        "source_key",
        "memory_key",
        "port",
        "pid",
        "tmp_path",
        "run_directory",
        "endpoint",
    )
)
_FIXED_TEST_BEARER = "Bearer m5-load-test-only"
_MAVEN_TEST_TARGET = "M5LoadHarnessTest"
_NANOSECONDS_PER_SECOND = 1_000_000_000
_RPC_WORKERS = 16
_MAX_PENDING_RPCS = 32
_WORKER_ACTIVITY_TIMEOUT_SECONDS = 10.0
_CHANNEL_READY_TIMEOUT_SECONDS = 5.0
_APPLICATION_READINESS_METHOD = "/m5.load.ApplicationReadiness/Ping"
_APPLICATION_READINESS_TIMEOUT_SECONDS = 5.0

_EVENT_RESPONSE_HEADER_KEY = "x-yilan-m5-event-response"
_EVENT_RESPONSE_HEADER_VALUE = "observed"
_CORRELATION_DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_SYNTHETIC_EVENT_ID_PATTERN = re.compile(
    r"^00000000-0000-4000-8000-[0-9]{12}$"
)


@dataclass(frozen=True, slots=True)
class OuterArguments:
    sessions: int
    read_rate: int
    event_rate: int
    duration_seconds: int
    profile: str
    output: Path
    is_canonical: bool
    test_hook_approved: bool = False


@dataclass(frozen=True, slots=True)
class PrivateArguments:
    run_directory: Path
    nonce: str
    profile: str


@dataclass(frozen=True, slots=True)
class _DispatchedOperation:
    index: int
    operation: ScheduledOperation
    started_at_ns: int
    acknowledged: bool


class _ScheduledOperationBucketMiss(RuntimeError):
    pass


class _ScheduledResolveDeadlineExceeded(RuntimeError):
    pass


class _ScheduledEventDeadlineExceeded(RuntimeError):
    __slots__ = ("_correlation_digest",)

    def __init__(self, correlation_digest: str) -> None:
        self._correlation_digest = _validate_correlation_digest(
            correlation_digest
        )
        super().__init__()

    @property
    def correlation_digest(self) -> str:
        return self._correlation_digest


class _ScheduledEventDeadlineBeforeDispatchComplete(
    _ScheduledEventDeadlineExceeded
):
    pass


class _ScheduledEventDeadlineAfterDispatchComplete(
    _ScheduledEventDeadlineExceeded
):
    pass


class _ScheduledEventDeadlineResponseHeaderObserved(
    _ScheduledEventDeadlineExceeded
):
    pass


class _ScheduledEventDeadlineResponseHeaderNotObserved(
    _ScheduledEventDeadlineExceeded
):
    pass


class _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderObserved(
    _ScheduledEventDeadlineBeforeDispatchComplete
):
    pass


class _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderNotObserved(
    _ScheduledEventDeadlineBeforeDispatchComplete
):
    pass


def _event_response_header_observation(error: grpc.RpcError) -> bool | None:
    try:
        initial_metadata = error.initial_metadata()
        if initial_metadata is None:
            return False
        marker_count = 0
        marker_valid = True
        for item in initial_metadata:
            if not isinstance(item, tuple) or len(item) != 2:
                return None
            key, value = item
            if key == _EVENT_RESPONSE_HEADER_KEY:
                marker_count += 1
                if value != _EVENT_RESPONSE_HEADER_VALUE:
                    marker_valid = False
    except Exception:
        return None
    if marker_count == 0:
        return False
    if marker_count == 1 and marker_valid:
        return True
    return None


def _validate_correlation_digest(value: object) -> str:
    if (
        not isinstance(value, str)
        or _CORRELATION_DIGEST_PATTERN.fullmatch(value) is None
    ):
        raise ValueError("event correlation digest must be lowercase SHA-256 hex")
    return value


def _event_correlation_digest(nonce: str, event_id: str) -> str:
    _validate_nonce(nonce)
    if (
        not isinstance(event_id, str)
        or _SYNTHETIC_EVENT_ID_PATTERN.fullmatch(event_id) is None
    ):
        raise ValueError("event correlation requires a synthetic event id")
    return hmac.new(
        nonce.encode("utf-8"),
        event_id.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


class _ScheduledDispatcherSaturated(RuntimeError):
    pass


class _ScheduledDispatcherBucketCrossed(RuntimeError):
    pass


class _ScheduledDispatcherBucketCrossedWithSaturatedPending(
    _ScheduledDispatcherBucketCrossed
):
    pass


class _ScheduledDispatcherBucketCrossedWithAvailablePending(
    _ScheduledDispatcherBucketCrossed
):
    pass


@dataclass(frozen=True, slots=True)
class _RunDirectoryGuard:
    root: Path
    child: Path
    root_identity: tuple[int, int, int]
    child_identity: tuple[int, int, int]

    @classmethod
    def capture(
        cls,
        child: str | Path,
        *,
        root: str | Path,
    ) -> _RunDirectoryGuard:
        root_path = _lexical_absolute(root)
        child_path = resolve_run_directory(child, root=root_path)
        root_info = _require_plain_directory(root_path, "run root")
        child_info = _require_plain_directory(child_path, "run directory")
        return cls(
            root=root_path,
            child=child_path,
            root_identity=_directory_identity(root_info),
            child_identity=_directory_identity(child_info),
        )

    def validate(self) -> Path:
        child_path = resolve_run_directory(self.child, root=self.root)
        root_info = _require_plain_directory(self.root, "run root")
        child_info = _require_plain_directory(child_path, "run directory")
        if _directory_identity(root_info) != self.root_identity:
            raise ValueError("run root identity changed after validation")
        if _directory_identity(child_info) != self.child_identity:
            raise ValueError("run directory identity changed after validation")
        return child_path

    def isolate(self) -> _QuarantineGuard:
        self.validate()
        quarantine = self.root / f".quarantine-{secrets.token_hex(16)}"
        if _lstat_if_present(quarantine) is not None:
            raise ValueError("owned quarantine path already exists")
        os.rename(self.child, quarantine)
        root_info = _require_plain_directory(self.root, "run root")
        quarantine_info = _require_plain_directory(
            quarantine,
            "owned quarantine",
        )
        if _directory_identity(root_info) != self.root_identity:
            raise ValueError("run root identity changed during quarantine isolation")
        if _directory_identity(quarantine_info) != self.child_identity:
            raise ValueError("owned quarantine identity changed during isolation")
        if _lstat_if_present(self.child) is not None:
            raise ValueError("owned run directory remained after quarantine isolation")
        return _QuarantineGuard(
            root=self.root,
            child=self.child,
            quarantine=quarantine,
            root_identity=self.root_identity,
            quarantine_identity=self.child_identity,
        )


@dataclass(frozen=True, slots=True)
class _QuarantineGuard:
    root: Path
    child: Path
    quarantine: Path
    root_identity: tuple[int, int, int]
    quarantine_identity: tuple[int, int, int]

    def validate_root(self) -> None:
        root_info = _require_plain_directory(self.root, "run root")
        if _directory_identity(root_info) != self.root_identity:
            raise ValueError("run root identity changed during quarantine cleanup")

    def validate(self) -> Path:
        self.validate_root()
        quarantine_info = _require_plain_directory(
            self.quarantine,
            "owned quarantine",
        )
        if _directory_identity(quarantine_info) != self.quarantine_identity:
            raise ValueError("owned quarantine identity changed during cleanup")
        if _lstat_if_present(self.child) is not None:
            raise ValueError("owned run directory reappeared during cleanup")
        return self.quarantine


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="run_memory_load.py",
        allow_abbrev=False,
        exit_on_error=False,
    )
    parser.add_argument("--loopback-client", action="store_true")
    parser.add_argument("--run-directory")
    parser.add_argument("--nonce")
    parser.add_argument("--sessions", type=int)
    parser.add_argument("--read-rate", type=int)
    parser.add_argument("--event-rate", type=int)
    parser.add_argument("--duration-seconds", type=int)
    parser.add_argument("--profile")
    parser.add_argument("--output")
    return parser


def _reject_duplicate_options(argv: Sequence[str]) -> None:
    seen: set[str] = set()
    for token in argv:
        if not isinstance(token, str):
            raise ValueError("arguments must be strings")
        option = token.split("=", 1)[0]
        if not option.startswith("--"):
            continue
        if option in seen:
            raise ValueError(f"duplicate option is not allowed: {option}")
        seen.add(option)


def parse_arguments(
    argv: Sequence[str],
    *,
    environ: Mapping[str, str] | None = None,
) -> OuterArguments | PrivateArguments:
    """Parse one exact mode and reject ambiguous/duplicate arguments."""

    _reject_duplicate_options(argv)
    try:
        namespace = _parser().parse_args(list(argv))
    except (argparse.ArgumentError, SystemExit) as error:
        raise ValueError("invalid load arguments") from error
    environment = os.environ if environ is None else environ

    if namespace.loopback_client:
        if environment.get(HARNESS_MARKER_ENV) != HARNESS_MARKER_VALUE:
            raise ValueError("private loopback client requires the harness marker")
        forbidden = (
            namespace.sessions,
            namespace.read_rate,
            namespace.event_rate,
            namespace.duration_seconds,
            namespace.output,
        )
        if any(value is not None for value in forbidden):
            raise ValueError("private loopback client received outer-only arguments")
        if namespace.run_directory is None:
            raise ValueError("private loopback client requires --run-directory")
        _validate_nonce(namespace.nonce)
        if namespace.profile not in _JAVA_PROFILES:
            raise ValueError("private loopback profile is not supported")
        return PrivateArguments(
            run_directory=Path(namespace.run_directory),
            nonce=namespace.nonce,
            profile=namespace.profile,
        )

    if namespace.run_directory is not None or namespace.nonce is not None:
        raise ValueError("outer mode cannot receive private arguments")
    dimensions = (
        namespace.sessions,
        namespace.read_rate,
        namespace.event_rate,
        namespace.duration_seconds,
    )
    if any(isinstance(value, bool) or not isinstance(value, int) for value in dimensions):
        raise ValueError("outer dimensions are required integers")
    if namespace.profile not in _OUTER_PROFILES:
        raise ValueError("outer profile is not supported")
    if not isinstance(namespace.output, str) or not namespace.output:
        raise ValueError("outer mode requires --output")
    canonical = dimensions == _CANONICAL_DIMENSIONS
    if not canonical:
        if environment.get(TEST_HOOK_ENV) != TEST_HOOK_VALUE:
            raise ValueError("non-canonical dimensions require the explicit test hook")
        if dimensions[:3] != _CANONICAL_DIMENSIONS[:3]:
            raise ValueError("test hook may change only canonical duration")
        if not 1 <= dimensions[3] < _CANONICAL_DIMENSIONS[3]:
            raise ValueError("test duration must be a bounded non-canonical smoke")
    return OuterArguments(
        sessions=dimensions[0],
        read_rate=dimensions[1],
        event_rate=dimensions[2],
        duration_seconds=dimensions[3],
        profile=namespace.profile,
        output=Path(namespace.output),
        is_canonical=canonical,
        test_hook_approved=not canonical,
    )


def resolve_run_directory(
    value: str | Path,
    *,
    root: str | Path | None = None,
) -> Path:
    """Validate one lexical, non-reparse child below tmp/memory-system/m5."""

    root_path = (
        Path.cwd() / "tmp" / "memory-system" / "m5"
        if root is None
        else Path(root)
    )
    root_path = _lexical_absolute(root_path)
    candidate = _lexical_absolute(value)
    if (
        candidate == root_path
        or candidate.parent != root_path
        or _RUN_NAME_PATTERN.fullmatch(candidate.name) is None
    ):
        raise ValueError("run directory must be one validated child below the M5 tmp root")
    _reject_reparse_chain(root_path)
    _reject_reparse_chain(candidate)
    return candidate


def _lexical_absolute(value: str | Path) -> Path:
    return Path(os.path.abspath(os.fspath(Path(value))))


def _lstat_if_present(path: Path) -> os.stat_result | None:
    try:
        return path.lstat()
    except FileNotFoundError:
        return None
    except OSError as error:
        raise ValueError("run path cannot be inspected safely") from error


def _is_reparse(info: object) -> bool:
    mode = getattr(info, "st_mode", None)
    if not isinstance(mode, int):
        raise ValueError("run path metadata is invalid")
    attributes = getattr(info, "st_file_attributes", 0)
    if isinstance(attributes, bool) or not isinstance(attributes, int):
        raise ValueError("run path attributes are invalid")
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return stat.S_ISLNK(mode) or bool(attributes & reparse_flag)


def _reject_reparse_chain(path: Path) -> None:
    chain = tuple(reversed((path, *path.parents)))
    for component in chain:
        info = _lstat_if_present(component)
        if info is not None and _is_reparse(info):
            raise ValueError("run path must not contain a reparse point")


def _require_plain_directory(path: Path, scope: str) -> os.stat_result:
    _reject_reparse_chain(path)
    info = _lstat_if_present(path)
    if info is None or not stat.S_ISDIR(info.st_mode):
        raise ValueError(f"{scope} must be an existing plain directory")
    return info


def _directory_identity(info: os.stat_result) -> tuple[int, int, int]:
    device = getattr(info, "st_dev", None)
    inode = getattr(info, "st_ino", None)
    mode = getattr(info, "st_mode", None)
    if any(isinstance(value, bool) or not isinstance(value, int) for value in (device, inode, mode)):
        raise ValueError("run directory identity is invalid")
    return (device, inode, stat.S_IFMT(mode))


def _prepare_run_root(value: str | Path) -> Path:
    root = _lexical_absolute(value)
    _reject_reparse_chain(root)
    root.mkdir(parents=True, exist_ok=True)
    _require_plain_directory(root, "run root")
    return root


def _delete_plain_directory_no_follow(
    path: Path,
    *,
    expected_identity: tuple[int, int, int],
) -> None:
    info = _require_plain_directory(path, "owned quarantine directory")
    if _directory_identity(info) != expected_identity:
        raise ValueError("owned quarantine directory identity changed")
    with os.scandir(path) as iterator:
        entries = tuple(iterator)
    info = _require_plain_directory(path, "owned quarantine directory")
    if _directory_identity(info) != expected_identity:
        raise ValueError("owned quarantine directory identity changed")
    for entry in entries:
        entry_path = path / entry.name
        entry_info = _lstat_if_present(entry_path)
        if entry_info is None:
            raise ValueError("owned quarantine entry disappeared during cleanup")
        if _is_reparse(entry_info):
            raise ValueError("owned quarantine must not contain a reparse point")
        if stat.S_ISDIR(entry_info.st_mode):
            _delete_plain_directory_no_follow(
                entry_path,
                expected_identity=_directory_identity(entry_info),
            )
        elif stat.S_ISREG(entry_info.st_mode):
            os.unlink(entry_path)
        else:
            raise ValueError("owned quarantine contains an unsupported entry")
        info = _require_plain_directory(path, "owned quarantine directory")
        if _directory_identity(info) != expected_identity:
            raise ValueError("owned quarantine directory identity changed")
    os.rmdir(path)


def _delete_quarantine_tree(guard: _QuarantineGuard) -> None:
    quarantine = guard.validate()
    _delete_plain_directory_no_follow(
        quarantine,
        expected_identity=guard.quarantine_identity,
    )


def _assert_cleanup_complete(guard: _QuarantineGuard) -> None:
    guard.validate_root()
    if (
        _lstat_if_present(guard.child) is not None
        or _lstat_if_present(guard.quarantine) is not None
    ):
        raise ValueError("owned child or quarantine remains after cleanup")


def _cleanup_owned_run(guard: _RunDirectoryGuard) -> None:
    quarantine_guard = guard.isolate()
    _delete_quarantine_tree(quarantine_guard)
    _assert_cleanup_complete(quarantine_guard)


def _validate_nonce(value: object) -> str:
    if not isinstance(value, str) or _NONCE_PATTERN.fullmatch(value) is None:
        raise ValueError("nonce must be one bounded opaque value")
    return value


def _no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def atomic_read_json(path: str | Path) -> dict[str, object]:
    source = Path(path)
    try:
        raw = source.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError("protocol JSON is missing or unreadable") from error
    if not raw or len(raw.encode("utf-8")) > 8_000_000:
        raise ValueError("protocol JSON size is invalid")
    try:
        value = json.loads(
            raw,
            object_pairs_hook=_no_duplicate_keys,
            parse_constant=lambda _value: (_ for _ in ()).throw(
                ValueError("non-finite JSON is forbidden")
            ),
        )
    except (json.JSONDecodeError, UnicodeError) as error:
        raise ValueError("protocol JSON is malformed") from error
    if not isinstance(value, dict):
        raise ValueError("protocol JSON must be an object")
    return value


def _encode_json(value: Mapping[str, object]) -> str:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    )


def atomic_write_json(path: str | Path, value: Mapping[str, object]) -> None:
    target = Path(path)
    if not target.parent.is_dir():
        raise ValueError("protocol JSON parent directory must already exist")
    encoded = _encode_json(value)
    temporary = target.with_name(f".{target.name}.{secrets.token_hex(8)}.tmp")
    try:
        temporary.write_text(encoded, encoding="utf-8", newline="\n")
        os.replace(temporary, target)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _atomic_create_json(path: str | Path, value: Mapping[str, object]) -> None:
    target = Path(path)
    if not target.parent.is_dir():
        raise ValueError("protocol JSON parent directory must already exist")
    temporary = target.with_name(f".{target.name}.{secrets.token_hex(8)}.tmp")
    try:
        temporary.write_text(_encode_json(value), encoding="utf-8", newline="\n")
        os.link(temporary, target)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def write_correlation_failure_record(
    run_directory: str | Path,
    *,
    nonce: str,
    profile: str,
    correlation_digest: str,
) -> None:
    _validate_nonce(nonce)
    if profile not in _JAVA_PROFILES:
        raise ValueError("correlation failure profile is invalid")
    digest = _validate_correlation_digest(correlation_digest)
    directory = Path(run_directory)
    if not directory.is_dir():
        raise ValueError("correlation failure directory must already exist")
    target = directory / CORRELATION_FAILURE_FILE_NAME
    if _lstat_if_present(target) is not None:
        raise ValueError("correlation failure record already exists")
    record = {
        "schema_version": PROTOCOL_SCHEMA_VERSION,
        "status": "failed",
        "nonce": nonce,
        "profile": profile,
        "correlation_digest": digest,
    }
    _require_exact_fields(
        record,
        _CORRELATION_FAILURE_FIELDS,
        "correlation failure record",
    )
    _atomic_create_json(target, record)


def _require_exact_fields(
    value: Mapping[str, object],
    expected: frozenset[str],
    scope: str,
) -> None:
    if set(value) != expected:
        raise ValueError(f"{scope} has unsupported or missing fields")


def _validate_loopback_endpoint(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("loopback endpoint must be 127.0.0.1:<port>")
    match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", value)
    if match is None:
        raise ValueError("loopback endpoint must be 127.0.0.1:<port>")
    port = int(match.group(1))
    if not 1 <= port <= 65535:
        raise ValueError("loopback endpoint port must be in 1..65535")
    return value


def _validate_protocol_identity(
    record: Mapping[str, object],
    *,
    expected_nonce: str,
    expected_profile: str,
) -> None:
    _validate_nonce(expected_nonce)
    if not isinstance(record.get("nonce"), str) or not hmac.compare_digest(
        record["nonce"], expected_nonce
    ):
        raise ValueError("protocol nonce mismatch")
    if record.get("profile") != expected_profile or expected_profile not in _JAVA_PROFILES:
        raise ValueError("protocol profile mismatch")
    if record.get("schema_version") != PROTOCOL_SCHEMA_VERSION:
        raise ValueError("protocol schema version mismatch")


def read_ready_record(
    path: str | Path,
    *,
    expected_nonce: str,
    expected_profile: str,
) -> dict[str, object]:
    record = atomic_read_json(path)
    _require_exact_fields(record, _READY_FIELDS, "ready record")
    _validate_protocol_identity(
        record,
        expected_nonce=expected_nonce,
        expected_profile=expected_profile,
    )
    if record.get("status") != "ready":
        raise ValueError("ready record status must be ready")
    _validate_loopback_endpoint(record.get("endpoint"))
    for field, expected in (
        ("sessions", 50),
        ("read_rate", 20),
        ("event_rate", 50),
    ):
        if record.get(field) != expected or isinstance(record.get(field), bool):
            raise ValueError(f"ready record {field} is invalid")
    duration = record.get("duration_seconds")
    if isinstance(duration, bool) or not isinstance(duration, int) or not 1 <= duration <= 600:
        raise ValueError("ready record duration_seconds is invalid")
    return record


def read_worker_active_record(
    path: str | Path,
    *,
    expected_nonce: str,
    expected_profile: str,
) -> dict[str, object]:
    record = atomic_read_json(path)
    _require_exact_fields(record, _WORKER_ACTIVE_FIELDS, "worker active record")
    _validate_protocol_identity(
        record,
        expected_nonce=expected_nonce,
        expected_profile=expected_profile,
    )
    if expected_profile != "full" or record.get("status") != "active":
        raise ValueError("worker active record status or profile is invalid")
    return record


def create_worker_active_record(
    path: str | Path,
    *,
    nonce: str,
    profile: str,
) -> dict[str, object]:
    if profile != "full":
        raise ValueError("worker active record is valid only for full")
    record: dict[str, object] = {
        "schema_version": PROTOCOL_SCHEMA_VERSION,
        "status": "active",
        "nonce": _validate_nonce(nonce),
        "profile": profile,
    }
    _atomic_create_json(path, record)
    return read_worker_active_record(
        path,
        expected_nonce=nonce,
        expected_profile=profile,
    )


def _reject_raw_report_keys(value: object, scope: str = "report") -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError(f"{scope} fields must be strings")
            if key.lower() in _RAW_REPORT_KEYS:
                raise ValueError(f"{scope} contains prohibited raw field")
            _reject_raw_report_keys(item, scope)
    elif isinstance(value, list):
        for item in value:
            _reject_raw_report_keys(item, scope)


def _finite_number(value: object, field: str) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
    ):
        raise ValueError(f"{field} must be finite")
    return float(value)


def _validate_profile_report_shape(
    report: object,
    *,
    expected_profile: str,
) -> dict[str, object]:
    if not isinstance(report, dict):
        raise ValueError("report must be an object")
    _reject_raw_report_keys(report)
    _require_exact_fields(report, _REPORT_FIELDS, "report")
    if report.get("profile") != expected_profile:
        raise ValueError("report profile mismatch")
    for field in ("counts", "critical_counters", "resources"):
        if not isinstance(report.get(field), dict):
            raise ValueError(f"report {field} must be an object")
    _finite_number(report.get("test_pipeline_answer_p95_ms"), "report answer p95")
    return report


def _validate_resource_availability(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("resource availability must be an object")
    _require_exact_fields(value, _RESOURCE_FIELDS, "resource availability")
    for field in ("cpu_time_ms", "java_working_set_bytes"):
        resource = value.get(field)
        if resource is not None and (
            isinstance(resource, bool) or not isinstance(resource, int) or resource < 0
        ):
            raise ValueError(f"resource availability {field} is invalid")
    if value.get("gpu_vram_bytes") is not None or value.get("gpu_reason") != _GPU_UNAVAILABLE_REASON:
        raise ValueError("resource availability GPU state is invalid")
    return value


def validate_outer_report(value: object) -> dict[str, object]:
    """Validate the redacted, promotion-only M5-7 report envelope."""

    if not isinstance(value, dict):
        raise ValueError("outer report must be an object")
    _reject_raw_report_keys(value, "outer report")
    _require_exact_fields(value, _OUTER_REPORT_FIELDS, "outer report")
    scope = value.get("measurement_scope")
    if not isinstance(scope, dict):
        raise ValueError("measurement scope must be an object")
    _require_exact_fields(scope, _MEASUREMENT_SCOPE_FIELDS, "measurement scope")
    if scope != _MEASUREMENT_SCOPE:
        raise ValueError("measurement scope is invalid")
    if value.get("answer_evidence_regression") != _ANSWER_EVIDENCE_REGRESSION:
        raise ValueError("answer evidence regression must stay separate")
    profiles = value.get("profiles")
    if not isinstance(profiles, dict) or not profiles:
        raise ValueError("profiles must be a non-empty object")
    if set(profiles) - _JAVA_PROFILES:
        raise ValueError("profiles contains an unsupported profile")
    for name, profile in profiles.items():
        _validate_profile_report_shape(profile, expected_profile=name)
    allowance = value.get("worker_on_allowance_ms")
    if "full" in profiles:
        _finite_number(allowance, "worker on allowance")
        if float(allowance) < 0:
            raise ValueError("worker on allowance must not be negative")
    elif allowance is not None:
        raise ValueError("worker on allowance is only valid for full")
    gates = value.get("gates")
    if not isinstance(gates, dict):
        raise ValueError("gates must be an object")
    _require_exact_fields(gates, _OUTER_GATE_FIELDS, "gates")
    if gates.get("profile_gates_passed") is not True or not isinstance(
        gates.get("canonical_600_second_run"), bool
    ):
        raise ValueError("gates are invalid")
    availability = _validate_resource_availability(value.get("resource_availability"))
    primary = profiles.get("full") or profiles.get("java-postgres-only")
    assert isinstance(primary, dict)
    if availability != primary.get("resources"):
        raise ValueError("resource availability does not match the measured profile")
    return value


def build_outer_report(
    *,
    profile_reports: Mapping[str, Mapping[str, object]],
    worker_off_p95_ms: float | None,
    is_canonical: bool,
) -> dict[str, object]:
    """Create the sole promotable report from already-gated profile aggregates."""

    if not isinstance(is_canonical, bool):
        raise ValueError("canonical label must be bool")
    profiles = json.loads(_encode_json(dict(profile_reports)))
    if not isinstance(profiles, dict):
        raise ValueError("profiles must be serializable")
    if "full" in profiles:
        if worker_off_p95_ms is None:
            raise ValueError("full profile requires a frozen worker-off baseline")
        allowance: float | None = freeze_worker_on_allowance_ms(worker_off_p95_ms)
    else:
        allowance = None
    primary = profiles.get("full") or profiles.get("java-postgres-only")
    if not isinstance(primary, dict):
        raise ValueError("profiles must contain a supported measured profile")
    resources = primary.get("resources")
    envelope = {
        "measurement_scope": dict(_MEASUREMENT_SCOPE),
        "answer_evidence_regression": _ANSWER_EVIDENCE_REGRESSION,
        "worker_on_allowance_ms": allowance,
        "profiles": profiles,
        "gates": {
            "profile_gates_passed": True,
            "canonical_600_second_run": is_canonical,
        },
        "resource_availability": resources,
    }
    return validate_outer_report(envelope)


def verify_canonical_report(
    outer_report: dict[str, object],
    *,
    is_canonical: bool,
) -> None:
    """Standalone canonical gate verification step.

    Called after ``build_outer_report`` and before ``atomic_write_json`` to
    provide a single, explicit assertion point for all canonical gate fields.
    Raises ``ValueError`` on any gate violation.
    """

    assert_canonical_report_gates(outer_report, is_canonical=is_canonical)


def read_done_record(
    path: str | Path,
    *,
    expected_nonce: str,
    expected_profile: str,
) -> dict[str, object]:
    record = atomic_read_json(path)
    _require_exact_fields(record, _DONE_FIELDS, "done record")
    _validate_protocol_identity(
        record,
        expected_nonce=expected_nonce,
        expected_profile=expected_profile,
    )
    if record.get("status") != "passed":
        raise ValueError("done record status must be passed")
    baseline = record.get("worker_off_p95_ms")
    if expected_profile == "full":
        if _finite_number(baseline, "worker_off_p95_ms") < 0:
            raise ValueError("worker_off_p95_ms must not be negative")
    elif baseline is not None:
        raise ValueError("worker_off_p95_ms is only valid for full")
    _validate_profile_report_shape(
        record.get("report"),
        expected_profile=expected_profile,
    )
    report = record["report"]
    assert isinstance(report, dict)
    if expected_profile == "answer-only":
        assert_profile_gates(report, worker_off_p95_ms=None)
    else:
        counts = report.get("counts")
        if not isinstance(counts, Mapping):
            raise ValueError("report counts are required")
        assert_profile_gates(
            report,
            worker_off_p95_ms=(float(baseline) if expected_profile == "full" else None),
            expected_resolve_attempts=counts.get("resolve_attempts"),
            expected_event_attempts=counts.get("event_attempts"),
        )
    return record


def _validate_java_home(environ: Mapping[str, str]) -> Path:
    raw = environ.get("JAVA_HOME")
    if not isinstance(raw, str) or not raw:
        raise ValueError("JAVA_HOME is required")
    java_home = Path(raw).resolve()
    candidates = (java_home / "bin" / "java.exe", java_home / "bin" / "java")
    if not java_home.is_dir() or not any(candidate.is_file() for candidate in candidates):
        raise ValueError("JAVA_HOME must contain a Java executable")
    return java_home


def _maven_wrapper_path() -> Path:
    service_root = Path(__file__).resolve().parents[1] / "services" / "memory-service"
    wrapper = service_root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if not wrapper.is_file():
        raise ValueError("approved Maven Wrapper is missing")
    return wrapper


def _schedule_counts(arguments: OuterArguments) -> tuple[int, int]:
    schedule = LoadSchedule.build(
        sessions=arguments.sessions,
        read_rate=arguments.read_rate,
        event_rate=arguments.event_rate,
        duration_seconds=arguments.duration_seconds,
        seed=41,
    )
    return (
        sum(operation.operation == "resolve" for operation in schedule),
        sum(operation.operation == "event" for operation in schedule),
    )


def _approved_maven_command(
    *,
    run_directory: Path,
    nonce: str,
    arguments: OuterArguments,
) -> list[str]:
    wrapper = _maven_wrapper_path()
    script = Path(__file__).resolve()
    return [
        str(wrapper),
        f"-Dtest={_MAVEN_TEST_TARGET}",
        f"-Dmemory.load.runDir={run_directory}",
        f"-Dmemory.load.python={Path(sys.executable).resolve()}",
        f"-Dmemory.load.script={script}",
        f"-Dmemory.load.profile={arguments.profile}",
        f"-Dmemory.load.durationSeconds={arguments.duration_seconds}",
        f"-Dmemory.load.nonce={nonce}",
        "test",
    ]


def run_outer(
    arguments: OuterArguments,
    *,
    environ: Mapping[str, str] | None = None,
    run_root: str | Path | None = None,
    subprocess_run: Callable[..., subprocess.CompletedProcess[Any]] = subprocess.run,
) -> dict[str, object]:
    """Run one approved harness target and promote only a gate-passing report."""

    if not isinstance(arguments, OuterArguments):
        raise ValueError("validated outer arguments are required")
    dimensions = (
        arguments.sessions,
        arguments.read_rate,
        arguments.event_rate,
        arguments.duration_seconds,
    )
    if arguments.profile not in _OUTER_PROFILES:
        raise ValueError("outer profile is not supported")
    if arguments.is_canonical != (dimensions == _CANONICAL_DIMENSIONS):
        raise ValueError("canonical label does not match validated dimensions")
    environment = dict(os.environ if environ is None else environ)
    if (
        not arguments.is_canonical
        and environment.get(TEST_HOOK_ENV) != TEST_HOOK_VALUE
    ):
        raise ValueError("non-canonical run requires the explicit test hook environment")
    if not arguments.is_canonical and (
        dimensions[:3] != _CANONICAL_DIMENSIONS[:3]
        or not 1 <= arguments.duration_seconds < _CANONICAL_DIMENSIONS[3]
    ):
        raise ValueError("internal smoke may change only canonical duration")
    _validate_java_home(environment)
    environment[HARNESS_MARKER_ENV] = HARNESS_MARKER_VALUE
    root = _prepare_run_root(
        Path.cwd() / "tmp" / "memory-system" / "m5"
        if run_root is None
        else Path(run_root)
    )
    nonce = secrets.token_urlsafe(32)
    run_directory = resolve_run_directory(
        root / f"run-{secrets.token_hex(12)}",
        root=root,
    )
    run_directory.mkdir()
    guard = _RunDirectoryGuard.capture(run_directory, root=root)
    expected_resolve, expected_event = _schedule_counts(arguments)
    output = arguments.output.resolve()
    report: dict[str, object] | None = None
    try:
        guard.validate()
        command = _approved_maven_command(
            run_directory=run_directory,
            nonce=nonce,
            arguments=arguments,
        )
        completed = subprocess_run(
            command,
            cwd=str(_maven_wrapper_path().parent),
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            errors="replace",
            timeout=arguments.duration_seconds + 180,
        )
        if completed.returncode != 0:
            raise RuntimeError("approved Maven load harness failed")
        guard.validate()
        done = read_done_record(
            run_directory / DONE_FILE_NAME,
            expected_nonce=nonce,
            expected_profile=arguments.profile,
        )
        guard.validate()
        report = done["report"]
        assert isinstance(report, dict)
        worker_off_p95 = (
            float(done["worker_off_p95_ms"])
            if arguments.profile == "full"
            else None
        )
        if arguments.profile == "answer-only":
            assert_profile_gates(report, worker_off_p95_ms=None)
        else:
            assert_profile_gates(
                report,
                worker_off_p95_ms=worker_off_p95,
                expected_resolve_attempts=expected_resolve,
                expected_event_attempts=expected_event,
            )
    finally:
        try:
            _cleanup_owned_run(guard)
        except (OSError, ValueError) as error:
            raise RuntimeError("owned run directory cleanup failed") from error
    if report is None:
        raise RuntimeError("approved Maven load harness produced no report")
    outer_report = build_outer_report(
        profile_reports={arguments.profile: report},
        worker_off_p95_ms=worker_off_p95,
        is_canonical=arguments.is_canonical,
    )
    verify_canonical_report(outer_report, is_canonical=arguments.is_canonical)
    atomic_write_json(output, outer_report)
    return outer_report


def _metadata_for_session(session_id: str) -> tuple[tuple[str, str], ...]:
    return (
        ("authorization", _FIXED_TEST_BEARER),
        ("x-memory-load-session", session_id),
    )


def _validate_client_result(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("client result must be an object")
    _require_exact_fields(value, _CLIENT_RESULT_FIELDS, "client result")
    counts = value.get("counts")
    if not isinstance(counts, dict) or set(counts) != {
        "attempts",
        "resolve_attempts",
        "event_attempts",
        "acknowledged_events",
    }:
        raise ValueError("client result counts are invalid")
    if any(
        isinstance(item, bool) or not isinstance(item, int) or item < 0
        for item in counts.values()
    ):
        raise ValueError("client result counters must be non-negative integers")
    for field in (
        "python_loopback_wait_samples_ns",
        "test_pipeline_answer_samples_ns",
    ):
        samples = value.get(field)
        if not isinstance(samples, list) or not samples:
            raise ValueError(f"client result {field} is required")
        if any(
            isinstance(sample, bool) or not isinstance(sample, int) or sample < 0
            for sample in samples
        ):
            raise ValueError(f"client result {field} must contain integer nanoseconds")
    if len(value["python_loopback_wait_samples_ns"]) != counts["attempts"]:
        raise ValueError("client result requires one loopback wait per attempt")
    if counts["attempts"] != counts["resolve_attempts"] + counts["event_attempts"]:
        raise ValueError("client result attempt counts are inconsistent")
    return value


def _await_loopback_channel_ready(channel: Any) -> None:
    """Wait for the private loopback transport before timed memory RPCs."""

    grpc.channel_ready_future(channel).result(timeout=_CHANNEL_READY_TIMEOUT_SECONDS)


def _await_loopback_application_ready(channel: grpc.Channel) -> None:
    if not isinstance(
        channel.unary_unary(
            _APPLICATION_READINESS_METHOD,
            request_serializer=empty_pb2.Empty.SerializeToString,
            response_deserializer=empty_pb2.Empty.FromString,
        )(
            empty_pb2.Empty(),
            timeout=_APPLICATION_READINESS_TIMEOUT_SECONDS,
        ),
        empty_pb2.Empty,
    ):
        raise RuntimeError("private application readiness response is invalid")


def _execute_loopback_workload(
    ready: Mapping[str, object],
    *,
    run_directory: Path,
    worker_endpoint: str | None,
    run_guard: _RunDirectoryGuard | None = None,
    before_probe: Callable[[], None] | None = None,
    after_probe: Callable[[], None] | None = None,
) -> dict[str, object]:
    """Perform deadline-bounded raw generated-stub calls and pipeline probes."""

    del worker_endpoint
    if (before_probe is None) != (after_probe is None):
        raise ValueError("probe lifecycle callbacks must be supplied together")
    if before_probe is not None and (
        not callable(before_probe) or not callable(after_probe)
    ):
        raise ValueError("probe lifecycle callbacks must be callable")
    endpoint = _validate_loopback_endpoint(ready["endpoint"])
    channel = grpc.insecure_channel(endpoint)
    try:
        _await_loopback_channel_ready(channel)
        _await_loopback_application_ready(channel)
        schedule = sorted(
            LoadSchedule.build(
                sessions=int(ready["sessions"]),
                read_rate=int(ready["read_rate"]),
                event_rate=int(ready["event_rate"]),
                duration_seconds=int(ready["duration_seconds"]),
                seed=41,
            ),
            key=lambda operation: operation.scheduled_at_ns,
        )
        port = LoopbackLoadMemoryPort(
            context_stub=memory_context_pb2_grpc.MemoryContextServiceStub(channel),
            event_stub=memory_event_pb2_grpc.MemoryEventServiceStub(channel),
            deadline_seconds=0.150,
            metadata_factory=_metadata_for_session,
        )
        probe_port = LoopbackLoadMemoryPort(
            context_stub=memory_context_pb2_grpc.MemoryContextServiceStub(channel),
            event_stub=memory_event_pb2_grpc.MemoryEventServiceStub(channel),
            deadline_seconds=0.150,
            metadata_factory=_metadata_for_session,
        )
        counts = {
            "attempts": 0,
            "resolve_attempts": 0,
            "event_attempts": 0,
            "acknowledged_events": 0,
        }
        if before_probe is None:
            outcomes = _dispatch_scheduled_operations(
                schedule,
                port=port,
                correlation_nonce=str(ready["nonce"]),
            )
            if run_guard is not None:
                run_guard.validate()
            answer_samples = _run_test_pipeline_probe(
                probe_port,
                run_directory=run_directory,
            )
        else:
            schedule_executor = ThreadPoolExecutor(
                max_workers=1,
                thread_name_prefix="m5-load-schedule",
            )
            schedule_future = schedule_executor.submit(
                _dispatch_scheduled_operations,
                schedule,
                port=port,
                correlation_nonce=str(ready["nonce"]),
            )
            probe_error: BaseException | None = None
            answer_samples: list[int] | None = None
            outcomes: list[_DispatchedOperation] | None = None
            try:
                try:
                    before_probe()
                    if run_guard is not None:
                        run_guard.validate()
                    answer_samples = _run_test_pipeline_probe(
                        probe_port,
                        run_directory=run_directory,
                    )
                except BaseException as error:
                    probe_error = error
                finally:
                    try:
                        after_probe()
                    except BaseException as error:
                        if probe_error is None:
                            probe_error = error
                if probe_error is None and run_guard is not None:
                    try:
                        run_guard.validate()
                    except BaseException as error:
                        probe_error = error
                try:
                    outcomes = schedule_future.result()
                except BaseException as error:
                    if probe_error is None:
                        probe_error = error
            finally:
                schedule_executor.shutdown(wait=True)
            if probe_error is not None:
                raise probe_error
            if outcomes is None or answer_samples is None:
                raise RuntimeError("full probe lifecycle produced no result")
        for outcome in outcomes:
            counts["attempts"] += 1
            if outcome.operation.operation == "resolve":
                counts["resolve_attempts"] += 1
            else:
                counts["event_attempts"] += 1
                if outcome.acknowledged:
                    counts["acknowledged_events"] += 1
        load_wait_samples = list(port.python_loopback_wait_samples_ns)
        if run_guard is not None:
            run_guard.validate()
        return _validate_client_result(
            {
                "counts": counts,
                "python_loopback_wait_samples_ns": load_wait_samples,
                "test_pipeline_answer_samples_ns": answer_samples,
            }
        )
    finally:
        channel.close()


def _dispatch_scheduled_operations(
    schedule: Sequence[ScheduledOperation],
    *,
    port: Any,
    correlation_nonce: str,
    clock_ns: Callable[[], int] = time.perf_counter_ns,
    sleep: Callable[[float], None] = time.sleep,
    executor_factory: Callable[..., Any] = ThreadPoolExecutor,
) -> list[_DispatchedOperation]:
    """Start each synchronous generated-stub call inside its schedule bucket."""

    if not callable(clock_ns) or not callable(sleep) or not callable(executor_factory):
        raise ValueError("bounded dispatcher dependencies must be callable")
    _validate_nonce(correlation_nonce)
    pending: set[Future[_DispatchedOperation]] = set()
    outcomes: list[_DispatchedOperation] = []

    def collect_completed() -> None:
        completed = tuple(future for future in pending if future.done())
        for future in completed:
            pending.remove(future)
            event_deadline_observed = False
            event_response_header_observation: bool | None = None
            try:
                outcome = future.result()
            except _ScheduledEventDeadlineResponseHeaderObserved as error:
                event_deadline_observed = True
                event_response_header_observation = True
                correlation_digest = error.correlation_digest
            except _ScheduledEventDeadlineResponseHeaderNotObserved as error:
                event_deadline_observed = True
                event_response_header_observation = False
                correlation_digest = error.correlation_digest
            except _ScheduledEventDeadlineExceeded as error:
                event_deadline_observed = True
                correlation_digest = error.correlation_digest
            if event_deadline_observed:
                if event_response_header_observation is True:
                    raise _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderObserved(
                        correlation_digest
                    ) from None
                if event_response_header_observation is False:
                    raise _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderNotObserved(
                        correlation_digest
                    ) from None
                raise _ScheduledEventDeadlineBeforeDispatchComplete(
                    correlation_digest
                ) from None
            outcomes.append(outcome)

    with executor_factory(
        max_workers=_RPC_WORKERS,
        thread_name_prefix="m5-load-rpc",
    ) as executor:
        workload_start_ns = clock_ns()
        if (
            isinstance(workload_start_ns, bool)
            or not isinstance(workload_start_ns, int)
            or workload_start_ns < 0
        ):
            raise ValueError("bounded dispatcher clock must return integer nanoseconds")
        for index, operation in enumerate(schedule):
            if not isinstance(operation, ScheduledOperation):
                raise ValueError("bounded dispatcher requires scheduled operations")
            due_ns = workload_start_ns + operation.scheduled_at_ns
            now_ns = clock_ns()
            if (
                isinstance(now_ns, bool)
                or not isinstance(now_ns, int)
                or now_ns < workload_start_ns
            ):
                raise ValueError("bounded dispatcher clock must be monotonic")
            remaining_ns = due_ns - now_ns
            if remaining_ns > 0:
                sleep(remaining_ns / _NANOSECONDS_PER_SECOND)
            dispatch_offset_ns = clock_ns() - workload_start_ns
            bucket_end_ns = (
                operation.bucket_second + 1
            ) * _NANOSECONDS_PER_SECOND
            if dispatch_offset_ns >= bucket_end_ns:
                if len(pending) >= _MAX_PENDING_RPCS:
                    raise _ScheduledDispatcherBucketCrossedWithSaturatedPending
                raise _ScheduledDispatcherBucketCrossedWithAvailablePending
            collect_completed()
            if len(pending) >= _MAX_PENDING_RPCS:
                raise _ScheduledDispatcherSaturated
            pending.add(
                executor.submit(
                    _invoke_scheduled_operation,
                    index,
                    operation,
                    port,
                    workload_start_ns,
                    clock_ns,
                    correlation_nonce,
                )
            )
            collect_completed()
        while pending:
            future = next(iter(pending))
            event_deadline_observed = False
            try:
                outcome = future.result()
            except _ScheduledEventDeadlineExceeded as error:
                event_deadline_observed = True
                correlation_digest = error.correlation_digest
            if event_deadline_observed:
                raise _ScheduledEventDeadlineAfterDispatchComplete(
                    correlation_digest
                ) from None
            outcomes.append(outcome)
            pending.remove(future)

    outcomes.sort(key=lambda item: item.index)
    if len(outcomes) != len(schedule):
        raise RuntimeError("bounded RPC dispatcher lost scheduled operations")
    return outcomes


def _invoke_scheduled_operation(
    index: int,
    operation: ScheduledOperation,
    port: Any,
    workload_start_ns: int,
    clock_ns: Callable[[], int],
    correlation_nonce: str,
) -> _DispatchedOperation:
    started_at_ns = clock_ns() - workload_start_ns
    bucket_start_ns = operation.bucket_second * _NANOSECONDS_PER_SECOND
    bucket_end_ns = (operation.bucket_second + 1) * _NANOSECONDS_PER_SECOND
    if (
        isinstance(started_at_ns, bool)
        or not isinstance(started_at_ns, int)
        or not bucket_start_ns <= started_at_ns < bucket_end_ns
    ):
        raise _ScheduledOperationBucketMiss(
            "scheduled operation worker start crossed its assigned bucket"
        )
    session_id = f"session-load-{operation.session_id:02d}"
    acknowledged = False
    if operation.operation == "resolve":
        try:
            port.resolve(
                MemoryRetrievalRequest(
                    session_id=session_id,
                    query_text="synthetic-load-probe",
                    task_type="load-probe",
                    budget=1,
                ),
                identity=None,
            )
        except grpc.RpcError as error:
            try:
                deadline_exceeded = error.code() is grpc.StatusCode.DEADLINE_EXCEEDED
            except Exception:
                deadline_exceeded = False
            if deadline_exceeded:
                raise _ScheduledResolveDeadlineExceeded from None
            raise
    elif operation.operation == "event":
        event_id = f"00000000-0000-4000-8000-{index:012d}"
        correlation_digest = _event_correlation_digest(
            correlation_nonce,
            event_id,
        )
        try:
            result = port.submit_event(
                InteractionEvent(
                    event_id=event_id,
                    session_id=session_id,
                    event_type="REFLECTION",
                    source="python.m5-load-test",
                    payload={},
                    privacy_level=PrivacyLevel.INTERNAL,
                ),
                identity=None,
                correlation_digest=correlation_digest,
            )
        except grpc.RpcError as error:
            try:
                deadline_exceeded = error.code() is grpc.StatusCode.DEADLINE_EXCEEDED
            except Exception:
                deadline_exceeded = False
            if deadline_exceeded:
                observation = _event_response_header_observation(error)
                if observation is True:
                    raise _ScheduledEventDeadlineResponseHeaderObserved(
                        correlation_digest
                    ) from None
                if observation is False:
                    raise _ScheduledEventDeadlineResponseHeaderNotObserved(
                        correlation_digest
                    ) from None
                raise _ScheduledEventDeadlineExceeded(
                    correlation_digest
                ) from None
            raise
        acknowledged = result.status.value in {"ACCEPTED", "DUPLICATE"}
    else:
        raise ValueError("scheduled operation type is invalid")
    return _DispatchedOperation(
        index=index,
        operation=operation,
        started_at_ns=started_at_ns,
        acknowledged=acknowledged,
    )


def _run_test_pipeline_probe(
    port: LoopbackLoadMemoryPort,
    *,
    run_directory: Path,
) -> list[int]:
    """Measure a deterministic reviewed-evidence AppPipeline probe."""

    controller = RetrievalController.for_offline_test()
    source = SourceRecord(
        title="M5 reviewed load probe",
        review_status=ReviewStatus.REVIEWED,
        authority_level="project_reviewed",
    )
    controller.source_registry.register_source(source)
    chunks = TextIngestor(controller.source_registry).ingest_text(
        source.source_id,
        "A wing produces lift through its governed interaction with airflow.",
        aircraft="TEST-AIRCRAFT",
        component="wing",
        concept="lift",
    )
    seed_controller_chunks(controller, chunks)
    base = RuntimeSettings.from_settings(load_settings("configs"))
    settings = replace(
        base,
        memory=MemorySettings(backend="remote", remote=base.memory.remote),
        langgraph=replace(
            base.langgraph,
            checkpoint_path=str(run_directory / "pipeline-checkpoints.sqlite3"),
        ),
    )
    verifier = LocalDemoSessionAssertionVerifier()
    identity = verifier.verify(verifier.issue("m5_load_probe"))
    if identity is None:
        raise RuntimeError("test identity verifier failed")
    pipeline = AppPipeline(
        retrieval_controller=controller,
        memory_controller=MemoryController(
            repository=SQLiteMemoryRepository(run_directory / "pipeline-memory.sqlite3")
        ),
        runtime_settings=settings,
        remote_memory_port=port,
        trace_enabled=False,
    )
    samples: list[int] = []
    try:
        for index in range(20):
            started_ns = time.perf_counter_ns()
            with trusted_identity_scope(identity):
                response = pipeline.run_text_query(
                    TextQueryRequest(
                        query="Explain lift using the reviewed evidence.",
                        run_id=f"m5-load-probe-{index:02d}",
                    )
                )
            elapsed_ns = time.perf_counter_ns() - started_ns
            if response.status != "ok" or not response.answer:
                raise RuntimeError("test pipeline probe failed")
            samples.append(elapsed_ns)
    finally:
        pipeline.close()
    return samples


def run_private_client(
    *,
    run_directory: str | Path,
    nonce: str,
    profile: str,
    run_root: str | Path | None = None,
    environ: Mapping[str, str] | None = None,
) -> dict[str, object]:
    """Run only when launched by the Java harness marker-gated private mode."""

    environment = os.environ if environ is None else environ
    if environment.get(HARNESS_MARKER_ENV) != HARNESS_MARKER_VALUE:
        raise ValueError("private loopback client requires the harness marker")
    if profile not in _JAVA_PROFILES:
        raise ValueError("private loopback profile is not supported")
    _validate_nonce(nonce)
    child = resolve_run_directory(run_directory, root=run_root)
    root = (
        _lexical_absolute(child.parent)
        if run_root is None
        else _lexical_absolute(run_root)
    )
    guard = _RunDirectoryGuard.capture(child, root=root)
    guard.validate()
    correlation_failure_path = child / CORRELATION_FAILURE_FILE_NAME
    if _lstat_if_present(correlation_failure_path) is not None:
        raise ValueError("correlation failure record already exists")
    ready = read_ready_record(
        child / READY_FILE_NAME,
        expected_nonce=nonce,
        expected_profile=profile,
    )
    guard.validate()
    server = None
    interference_barrier: FakeWorkerInterferenceBarrier | None = None
    worker_endpoint: str | None = None
    result: dict[str, object] | None = None
    workload_error: BaseException | None = None
    barrier_released = False

    def release_interference_barrier() -> None:
        nonlocal barrier_released
        if interference_barrier is None or barrier_released:
            return
        interference_barrier.release()
        barrier_released = True

    try:
        if profile == "full":
            if _lstat_if_present(child / WORKER_ACTIVE_FILE_NAME) is not None:
                raise ValueError("worker active record already exists")
            interference_barrier = FakeWorkerInterferenceBarrier()
            server = build_fake_server(
                max_workers=1,
                interference_barrier=interference_barrier,
            )
            worker_port = server.add_insecure_port("127.0.0.1:0")
            worker_endpoint = _validate_loopback_endpoint(
                f"127.0.0.1:{worker_port}"
            )
            server.start()
            guard.validate()
            atomic_write_json(
                child / WORKER_READY_FILE_NAME,
                {
                    "schema_version": PROTOCOL_SCHEMA_VERSION,
                    "status": "ready",
                    "nonce": nonce,
                    "profile": profile,
                    "endpoint": worker_endpoint,
                },
            )
            guard.validate()

        def before_probe() -> None:
            if interference_barrier is None:
                raise RuntimeError("full worker interference barrier is missing")
            if not interference_barrier.wait_until_active(
                _WORKER_ACTIVITY_TIMEOUT_SECONDS
            ):
                raise RuntimeError("fake worker activity was not observed before timeout")
            guard.validate()
            create_worker_active_record(
                child / WORKER_ACTIVE_FILE_NAME,
                nonce=nonce,
                profile=profile,
            )
            guard.validate()

        guard.validate()
        result = _execute_loopback_workload(
            ready,
            run_directory=child,
            worker_endpoint=worker_endpoint,
            run_guard=guard,
            before_probe=(before_probe if profile == "full" else None),
            after_probe=(
                release_interference_barrier if profile == "full" else None
            ),
        )
        guard.validate()
        result = _validate_client_result(result)
        if profile == "full":
            read_worker_active_record(
                child / WORKER_ACTIVE_FILE_NAME,
                expected_nonce=nonce,
                expected_profile=profile,
            )
    except BaseException as error:
        workload_error = error
    finally:
        if interference_barrier is not None:
            try:
                release_interference_barrier()
            except BaseException as error:
                if workload_error is None:
                    workload_error = RuntimeError(
                        "fake worker interference barrier release failed"
                    )
                    workload_error.__cause__ = error
        if server is not None:
            try:
                termination = server.stop(0)
                wait = getattr(termination, "wait", None)
                if not callable(wait) or wait(timeout=5) is not True:
                    raise RuntimeError("fake worker shutdown did not complete")
            except BaseException as error:
                if workload_error is None:
                    workload_error = RuntimeError(
                        "fake worker shutdown failed"
                    )
                    workload_error.__cause__ = error
        if interference_barrier is not None and workload_error is None:
            try:
                validator = getattr(
                    interference_barrier,
                    "validate_single_entry",
                    None,
                )
                if callable(validator):
                    validator()
                guard.validate()
                read_worker_active_record(
                    child / WORKER_ACTIVE_FILE_NAME,
                    expected_nonce=nonce,
                    expected_profile=profile,
                )
            except BaseException as error:
                workload_error = RuntimeError(
                    "fake worker interference activity validation failed"
                )
                workload_error.__cause__ = error
    if workload_error is not None:
        if isinstance(workload_error, _ScheduledEventDeadlineExceeded):
            guard.validate()
            write_correlation_failure_record(
                child,
                nonce=nonce,
                profile=profile,
                correlation_digest=workload_error.correlation_digest,
            )
            guard.validate()
        raise workload_error
    if result is None:
        raise RuntimeError("private load workload produced no result")
    guard.validate()
    if profile == "full":
        read_worker_active_record(
            child / WORKER_ACTIVE_FILE_NAME,
            expected_nonce=nonce,
            expected_profile=profile,
        )
    record = {
        "schema_version": PROTOCOL_SCHEMA_VERSION,
        "status": "passed",
        "nonce": nonce,
        "profile": profile,
        "result": result,
    }
    _require_exact_fields(record, _CLIENT_DONE_FIELDS, "client done record")
    atomic_write_json(child / CLIENT_DONE_FILE_NAME, record)
    guard.validate()
    return record


def main(argv: Sequence[str] | None = None) -> int:
    parsed = parse_arguments(
        sys.argv[1:] if argv is None else argv,
        environ=os.environ,
    )
    if isinstance(parsed, PrivateArguments):
        try:
            run_private_client(
                run_directory=parsed.run_directory,
                nonce=parsed.nonce,
                profile=parsed.profile,
                environ=os.environ,
            )
        except Exception as error:
            if isinstance(error, _ScheduledDispatcherBucketCrossedWithSaturatedPending):
                return 49
            if isinstance(error, _ScheduledDispatcherBucketCrossedWithAvailablePending):
                return 50
            if isinstance(error, _ScheduledDispatcherBucketCrossed):
                return 48
            if isinstance(error, _ScheduledDispatcherSaturated):
                return 47
            if isinstance(error, _ScheduledResolveDeadlineExceeded):
                return 45
            if isinstance(
                error,
                _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderObserved,
            ):
                return 53
            if isinstance(
                error,
                _ScheduledEventDeadlineBeforeDispatchCompleteResponseHeaderNotObserved,
            ):
                return 54
            if isinstance(error, _ScheduledEventDeadlineBeforeDispatchComplete):
                return 51
            if isinstance(error, _ScheduledEventDeadlineAfterDispatchComplete):
                return 52
            if isinstance(error, _ScheduledEventDeadlineExceeded):
                return 46
            if isinstance(error, grpc.RpcError):
                try:
                    if error.code() is grpc.StatusCode.DEADLINE_EXCEEDED:
                        return 41
                except Exception:
                    return 44
            if isinstance(error, _ScheduledOperationBucketMiss):
                return 42
            if isinstance(error, ValueError):
                return 43
            return 44
    else:
        run_outer(parsed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
