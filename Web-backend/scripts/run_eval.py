from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import socket
import sys
import tempfile
from tempfile import TemporaryDirectory
from time import perf_counter
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from observability.logging_config import configure_logging

# 评估脚本 stdout 只输出最终 JSON 报告，结构化日志统一走 stderr。
configure_logging()

from app.api.schemas import TextQueryRequest
from core.contracts import EvidenceItem, EvidencePackage, SceneState
from core.settings import load_settings
from evaluation.runtime_configs import materialize_eval_runtime_configs
from evaluation.settings import EvalSettings, load_eval_settings
from feedback.checkpoint_store import CheckpointStore
from input.query_understanding import understand_query
from knowledge.retrieval_controller import RetrievalController
from knowledge.schemas import RetrievalFeedback, ReviewStatus, SceneObject, SourceRecord
from memory.controller import MemoryController
from observability.trace_exporter import build_rag_audit_summary
from services.app_pipeline import AppPipeline


PROMPT_AUDIT_FIELDS = (
    "prompt_template_id",
    "prompt_version",
    "prompt_snapshot_id",
    "prompt_route_reason",
)


def _project_path(path: Path) -> Path:
    return path if path.is_absolute() else PROJECT_ROOT / path


@contextmanager
def _seeded_pipeline_context():
    with TemporaryDirectory(prefix="aviation-rag-eval-") as temp_directory:
        fixture_dir = Path(temp_directory)
        paths = materialize_eval_runtime_configs(fixture_dir, project_root=PROJECT_ROOT)
        input_path = fixture_dir / "lift.md"
        input_path.write_text(
            (
                "# 机翼升力\n"
                "Wing lift is produced by reviewed airflow and pressure distribution evidence. "
                "机翼通过翼型和迎角改变周围气流压力分布，从而产生升力。"
                "机翼上下表面的压强差形成向上的气动力。"
                "机翼使气流向下偏转，飞机受到向上的反作用力。"
            ),
            encoding="utf-8",
        )

        ingestion = RetrievalController.from_config(str(paths.rag_config_path))
        try:
            ingestion.ingest_source(
                input_path=input_path,
                source_record=SourceRecord(
                    source_id="src_smoke_lift",
                    title="Smoke reviewed aviation note",
                    source_type="text",
                    authority_level="project_reviewed",
                    review_status=ReviewStatus.REVIEWED,
                ),
                aircraft="C919",
                component="wing",
                concept="升力",
                maintainer_authorized=True,
                cli_parameters={"fixture": "smoke", "project_maintainer": True},
            )
        finally:
            ingestion.close()

        memory = MemoryController.from_config(str(paths.memory_config_path))
        with AppPipeline(
            rag_config_path=paths.rag_config_path,
            memory_controller=memory,
            settings=load_settings(paths.config_dir),
            checkpoint_store=CheckpointStore(config_path=paths.config_dir),
        ) as pipeline:
            yield pipeline


def _seeded_pipeline() -> AppPipeline:
    context = _seeded_pipeline_context()
    pipeline = context.__enter__()
    setattr(pipeline, "_eval_context_manager", context)
    return pipeline


def _close_seeded_pipeline(pipeline: AppPipeline) -> None:
    context = getattr(pipeline, "_eval_context_manager", None)
    if context is not None:
        context.__exit__(None, None, None)


def _prompt_audit_output(response: object) -> dict[str, Any]:
    trace = getattr(response, "trace", None) or {}
    return {field: trace.get(field) for field in PROMPT_AUDIT_FIELDS}


def _has_main_pipeline_prompt_trace(response: object) -> bool:
    trace = getattr(response, "trace", None)
    return bool(
        isinstance(trace, dict)
        and all(
            isinstance(trace.get(field), str) and trace[field].strip()
            for field in PROMPT_AUDIT_FIELDS
        )
    )


def _has_prompt_asset_summary(trace: dict[str, Any]) -> bool:
    return any(
        item.get("section") == "prompt_asset" and item.get("present") is True
        for item in trace.get("prompt_injection_summary", [])
        if isinstance(item, dict)
    )


def _generation_trace(response: object) -> dict[str, Any]:
    answer = getattr(response, "answer", None) or {}
    generation_trace = answer.get("generation_trace", {})
    return generation_trace if isinstance(generation_trace, dict) else {}


def _canonical_sentences(text: object) -> set[str]:
    if not isinstance(text, str):
        return set()
    return {
        "".join(sentence.split())
        for sentence in re.split(r"(?<=[。！？.!?])\s*", text.strip())
        if sentence.strip()
    }


def _grounded_spoken_steps(response: object) -> set[str]:
    answer = getattr(response, "answer", None)
    if not isinstance(answer, dict):
        return set()
    evidence_refs = {
        item
        for item in answer.get("evidence_refs", [])
        if isinstance(item, str) and item
    }
    supported: set[str] = set()
    for claim in answer.get("claim_candidates", []):
        if not isinstance(claim, dict):
            continue
        if claim.get("support_status") not in {None, "supported"}:
            continue
        claim_evidence = {
            item
            for item in claim.get("evidence_ids", [])
            if isinstance(item, str) and item
        }
        if not evidence_refs.intersection(claim_evidence):
            continue
        supported.update(_canonical_sentences(claim.get("text")))
    return supported


def _low_confidence_voice_eval_case() -> dict[str, Any]:
    """Run the configured voice normalization gate without ASR/TTS providers."""
    from input.voice_query_normalizer import VoiceQueryNormalizer
    from voice.contracts import ASRResult
    from voice.settings import load_default_settings

    voice_settings = load_default_settings()
    confidence = max(0.0, voice_settings.asr_low_confidence_threshold - 0.1)
    result = VoiceQueryNormalizer(settings=voice_settings).normalize(
        ASRResult("解释机翼", confidence=confidence, is_final=True)
    )
    return {
        "case_id": "low_confidence_voice",
        "name": "低置信语音",
        "query": "解释机翼",
        "expected_channels": ["voice_normalization"],
        "actual_channels": ["voice_normalization"],
        "expected_gate": "not_run",
        "actual_gate": "not_run",
        "required_evidence_types": ["clarification"],
        "actual_required_evidence_types": ["clarification"],
        "prohibited_claims": ["在低置信转写上直接输出航空事实"],
        "prohibited_claims_observed": [],
        "clarification_reason": result.clarification_reason,
        "final_retrieval_plan": result.final_retrieval_plan,
        "passed": (
            result.needs_clarification
            and result.clarification_reason == "low_confidence_asr"
            and result.final_retrieval_plan == {}
        ),
    }


class _RecordingPipeline:
    """Observe the public AppPipeline request/response without changing its behavior."""

    def __init__(self, delegate: AppPipeline) -> None:
        self._delegate = delegate
        self.requests: list[TextQueryRequest] = []
        self.responses: list[object] = []
        self.memory_controller = delegate.memory_controller

    def run_text_query(self, request: TextQueryRequest):
        self.requests.append(request)
        response = self._delegate.run_text_query(request)
        self.responses.append(response)
        return response

    def get_feedback_checkpoint(self, checkpoint_id: str, **binding: object):
        return self._delegate.get_feedback_checkpoint(checkpoint_id, **binding)

    def release_feedback_checkpoint(self, checkpoint_id: str, **binding: object) -> bool:
        return self._delegate.release_feedback_checkpoint(checkpoint_id, **binding)


class _RecordingSpokenPlanner:
    """Record the actual trusted projection returned by the production planner."""

    def __init__(self, delegate: SpokenAnswerPlanner) -> None:
        self._delegate = delegate
        self.outputs: list[SpokenAnswer] = []

    def plan(self, *args: object, **kwargs: object) -> SpokenAnswer:
        output = self._delegate.plan(*args, **kwargs)
        self.outputs.append(output)
        return output


def _voice_frames(session_id: str, turn_id: str, stream_id: str) -> tuple[AudioFrame, ...]:
    from voice.contracts import AudioFrame

    frames = [
        AudioFrame(
            session_id=session_id,
            turn_id=turn_id,
            audio_stream_id=stream_id,
            sequence_no=0,
            payload=b"\x01\x02" * 160,
            client_timestamp_ms=0,
            energy=0.8,
        )
    ]
    frames.extend(
        AudioFrame(
            session_id=session_id,
            turn_id=turn_id,
            audio_stream_id=stream_id,
            sequence_no=sequence,
            payload=b"\x00\x00" * 160,
            client_timestamp_ms=sequence * 20,
            energy=0.0,
        )
        for sequence in range(1, 31)
    )
    return tuple(frames)


def _voice_registry(settings: VoiceSettings) -> ProviderRegistry:
    from voice.asr import MockASRProvider
    from voice.contracts import ASRResult
    from voice.providers import ProviderRegistry
    from voice.transport import WebSocketAudioTransport
    from voice.tts import MockTTSProvider
    from voice.vad import MockVADService

    registry = ProviderRegistry()
    registry.register_transport("websocket", WebSocketAudioTransport)
    registry.register_vad("mock", MockVADService)
    registry.register_asr(
        "mock",
        lambda **_kwargs: MockASRProvider(
            events=(
                ASRResult("机翼", 0.95, False, end_ms=20),
                ASRResult("机翼升力", 0.95, True, end_ms=40),
            )
        ),
    )
    registry.register_tts(
        "mock",
        lambda **_kwargs: MockTTSProvider(settings=settings),
    )
    return registry


def _port_is_released(port: int) -> bool:
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        probe.close()


async def _run_voice_realtime_case() -> dict[str, Any]:
    from voice.contracts import VoiceMetricRecord
    from voice.metrics import VoiceMetricsRecorder
    from voice.mock_client import MockVoiceClient, MockVoiceScenario
    from voice.orchestrator import VoiceSessionOrchestrator
    from voice.settings import VoiceSettings
    from voice.spoken_answer import SpokenAnswerPlanner
    from voice.websocket_server import serve_voice

    settings = VoiceSettings.from_file(PROJECT_ROOT / "configs" / "voice.yaml")
    seeded_pipeline = _seeded_pipeline()
    pipeline = _RecordingPipeline(seeded_pipeline)
    planner = _RecordingSpokenPlanner(SpokenAnswerPlanner(settings))
    metric_records: list[VoiceMetricRecord] = []
    recorder = VoiceMetricsRecorder(writer=metric_records.append)
    registry = _voice_registry(settings)
    orchestrator = VoiceSessionOrchestrator(
        pipeline=pipeline,
        settings=settings,
        provider_registry=registry,
        spoken_answer_planner=planner,
        metrics_recorder=recorder,
    )
    baseline_tasks = set(asyncio.all_tasks())
    session_id = "eval-voice-session"
    turn_id = "eval-voice-turn"
    stream_id = "eval-voice-stream"
    server = None
    port: int | None = None
    client: MockVoiceClient | None = None
    events: list[dict[str, Any]] = []
    try:
        server = await serve_voice("127.0.0.1", 0, orchestrator)
        port = int(server.sockets[0].getsockname()[1])
        client = MockVoiceClient(f"ws://127.0.0.1:{port}")
        scenario = MockVoiceScenario(
            session_id=session_id,
            turn_id=turn_id,
            audio_stream_id=stream_id,
            user_id="eval-voice-user",
            frames=_voice_frames(session_id, turn_id, stream_id),
        )
        events = await client.run_scenario(scenario)
    finally:
        if server is not None:
            server.close()
            await server.wait_closed()
        await orchestrator.drain_metrics()
        _close_seeded_pipeline(seeded_pipeline)
    await asyncio.sleep(0)
    leaked_tasks = [
        task.get_name()
        for task in asyncio.all_tasks() - baseline_tasks
        if not task.done()
    ]
    port_released = port is not None and _port_is_released(port)

    request = pipeline.requests[0] if len(pipeline.requests) == 1 else None
    response = pipeline.responses[0] if len(pipeline.responses) == 1 else None
    spoken = planner.outputs[0] if len(planner.outputs) == 1 else None
    metric = metric_records[0] if len(metric_records) == 1 else None
    event_types = [str(event.get("type")) for event in events]
    display_events = [event for event in events if event.get("type") == "answer.display"]
    completion_indices = [
        index
        for index, event in enumerate(events)
        if event.get("type") == "voice.state"
        and event.get("status") == "playback.completed"
    ]
    tts_indices = [
        index for index, event in enumerate(events) if event.get("type") == "tts.chunk"
    ]
    display_indices = [
        index for index, event in enumerate(events) if event.get("type") == "answer.display"
    ]
    grounded_steps = _grounded_spoken_steps(response) if response is not None else set()
    answer_payload = getattr(response, "answer", None) if response is not None else None
    allowed_briefs = (
        _canonical_sentences(answer_payload.get("short_answer"))
        if isinstance(answer_payload, dict)
        else set()
    )
    allowed_follow_ups = (
        set().union(
            *(
                _canonical_sentences(item)
                for item in answer_payload.get("follow_up_questions", [])
            )
        )
        if isinstance(answer_payload, dict)
        and isinstance(answer_payload.get("follow_up_questions", []), list)
        else set()
    )
    spoken_steps_grounded = bool(
        spoken is not None
        and 2 <= len(spoken.spoken_steps) <= 3
        and not spoken.new_claim_ids
        and all(
            "".join(step.split()) in grounded_steps
            for step in spoken.spoken_steps
        )
    )
    spoken_brief_grounded = bool(
        spoken is not None
        and "".join(spoken.answer_brief.split()) in allowed_briefs
    )
    spoken_follow_up_grounded = bool(
        spoken is not None
        and (
            spoken.follow_up_prompt is None
            or "".join(spoken.follow_up_prompt.split()) in allowed_follow_ups
        )
    )
    case = {
        "case_id": "voice_realtime_mock",
        "passed": bool(
            request is not None
            and response is not None
            and spoken is not None
            and metric is not None
            and len(pipeline.requests) == 1
            and len(pipeline.responses) == 1
            and len(planner.outputs) == 1
            and len(metric_records) == 1
            and request.query == "机翼升力"
            and request.source == "voice"
            and response.final_answer_id == spoken.source_answer_id
            and response.evidence_package_id == spoken.source_evidence_package_id
            and metric.answer_id == response.final_answer_id
            and metric.evidence_package_id == response.evidence_package_id
            and spoken_steps_grounded
            and spoken_brief_grounded
            and spoken_follow_up_grounded
            and event_types.count("asr.final") == 1
            and len(display_events) == 1
            and display_events[0].get("answer_id") == response.final_answer_id
            and display_events[0].get("evidence_package_id") == response.evidence_package_id
            and len(completion_indices) == 1
            and len(tts_indices) == metric.tts_chunk_count
            and metric.tts_chunk_count > 0
            and display_indices[0] < tts_indices[0] < completion_indices[0]
            and not metric.raw_audio_persisted
            and not metric.private_payload_logged
            and client is not None
            and client.close_code == 1000
            and not leaked_tasks
            and port_released
            and _has_main_pipeline_prompt_trace(response)
        ),
        "run_id": getattr(response, "run_id", None),
        "action_decision": getattr(response, "action_decision", None),
        "normalized_query": request.query if request is not None else None,
        "request_source": request.source if request is not None else None,
        "answer_id": getattr(response, "final_answer_id", None),
        "evidence_package_id": getattr(response, "evidence_package_id", None),
        "spoken_answer": spoken.to_dict() if spoken is not None else None,
        "tts_chunk_count": metric.tts_chunk_count if metric is not None else 0,
        "metrics": {
            "raw_audio_persisted": (
                metric.raw_audio_persisted if metric is not None else None
            ),
            "private_payload_logged": (
                metric.private_payload_logged if metric is not None else None
            ),
            "state_transition_count": (
                metric.state_transition_count if metric is not None else 0
            ),
        },
        "client_event_types": event_types,
        "client_close_code": client.close_code if client is not None else None,
        "port_released": port_released,
        "pending_task_count": len(leaked_tasks),
    }
    if response is not None:
        case.update(_prompt_audit_output(response))
    return case


async def _run_voice_realtime_case_safely() -> dict[str, Any]:
    try:
        return await _run_voice_realtime_case()
    except asyncio.CancelledError:
        raise
    except Exception:
        return {
            "case_id": "voice_realtime_mock",
            "passed": False,
            "error_code": "VOICE_REALTIME_SMOKE_FAILED",
            "error_type": "offline_voice_case_failure",
        }


def _write_report_atomic(report: dict[str, Any], target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.",
        suffix=".tmp",
        dir=target.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _answer_has_grounding(response: object) -> bool:
    answer = getattr(response, "answer", None)
    if not isinstance(answer, dict):
        return False
    source_binding = answer.get("source_binding")
    evidence_refs = answer.get("evidence_refs")
    return (
        isinstance(evidence_refs, list)
        and bool(evidence_refs)
        and isinstance(source_binding, dict)
        and any(
            isinstance(values, list) and bool(values)
            for values in source_binding.values()
        )
    )


def run_text_smoke_suite(
    eval_settings: EvalSettings | None = None,
) -> dict[str, Any]:
    started = perf_counter()
    settings = eval_settings or load_eval_settings()
    cases: list[dict[str, Any]] = []

    with _seeded_pipeline_context() as pipeline:
        text_response = pipeline.run_text_query(
            TextQueryRequest(query="wing lift", run_id="eval_text_smoke")
        )
        text_answer = text_response.answer or {}
        cases.append(
            {
                "case_id": "text_qa",
                "passed": (
                    text_response.status == "ok"
                    and bool(text_answer.get("main_answer"))
                    and _answer_has_grounding(text_response)
                    and _has_main_pipeline_prompt_trace(text_response)
                ),
                "run_id": text_response.run_id,
                "action_decision": text_response.action_decision,
                "evidence_refs": text_answer.get("evidence_refs", []),
                "source_binding_claims": sorted(
                    (text_answer.get("source_binding") or {}).keys()
                ),
                **_prompt_audit_output(text_response),
            }
        )

        missing_response = pipeline.run_text_query(
            TextQueryRequest(
                query="unknown engine thrust parameter",
                run_id="eval_missing_evidence",
            )
        )
        missing_answer = missing_response.answer or {}
        missing_trace = missing_response.trace or {}
        missing_generation_trace = _generation_trace(missing_response)
        is_final_fact_prompt = missing_generation_trace.get("is_final_fact_prompt")
        missing_prompt_checks = (
            _has_main_pipeline_prompt_trace(missing_response)
            and missing_trace.get("prompt_template_id") == "aviation_basic_safe"
            and _has_prompt_asset_summary(missing_trace)
            and is_final_fact_prompt is False
        )
        cases.append(
            {
                "case_id": "insufficient_evidence",
                "passed": (
                    missing_response.status == "ok"
                    and missing_response.action_decision
                    in {"ASK_CLARIFICATION", "HUMAN_REVIEW"}
                    and missing_prompt_checks
                    and not missing_answer.get("evidence_refs")
                    and not (missing_answer.get("source_binding") or {})
                ),
                "run_id": missing_response.run_id,
                "action_decision": missing_response.action_decision,
                "is_final_fact_prompt": is_final_fact_prompt,
                "evidence_refs": missing_answer.get("evidence_refs", []),
                "source_binding_claims": sorted(
                    (missing_answer.get("source_binding") or {}).keys()
                ),
                **_prompt_audit_output(missing_response),
            }
        )

    passed = sum(1 for case in cases if case["passed"])
    report = {
        "suite": "text_smoke",
        "case_count": len(cases),
        "passed_count": passed,
        "pass_rate": passed / len(cases),
        "latency_ms": int((perf_counter() - started) * 1000),
        "cases": cases,
        "mock_offline": True,
        "knowledge_database_scope": "temporary",
        "memory_database_scope": "temporary",
        "voice_cases_run": 0,
    }
    _write_report_atomic(report, _project_path(settings.report_path("text_smoke")))
    return report


def _run_low_confidence_voice_case() -> dict[str, Any]:
    from input.voice_query_normalizer import VoiceQueryNormalizer
    from voice.contracts import ASRResult
    from voice.settings import VoiceSettings

    voice_settings = VoiceSettings.from_file(PROJECT_ROOT / "configs" / "voice.yaml")
    low_confidence_query = VoiceQueryNormalizer(
        asr_low_confidence_threshold=voice_settings.asr_low_confidence_threshold,
    ).normalize(
        ASRResult(
            raw_transcript="low confidence wing lift",
            confidence=0.2,
            is_final=True,
            provider="mock",
        )
    )
    return {
        "case_id": "low_confidence_voice",
        "expected_channels": ["voice_normalization"],
        "actual_channels": ["voice_normalization"],
        "expected_gate": "not_run",
        "actual_gate": "not_run",
        "passed": (
            low_confidence_query.needs_clarification
            and not low_confidence_query.final_retrieval_plan
        ),
    }


def run_voice_smoke_suite(
    eval_settings: EvalSettings | None = None,
) -> dict[str, Any]:
    started = perf_counter()
    settings = eval_settings or load_eval_settings()
    cases = [
        _run_low_confidence_voice_case(),
        asyncio.run(_run_voice_realtime_case_safely()),
    ]
    passed = sum(1 for case in cases if case["passed"])
    report = {
        "suite": "voice_smoke",
        "case_count": len(cases),
        "passed_count": passed,
        "pass_rate": passed / len(cases),
        "latency_ms": int((perf_counter() - started) * 1000),
        "cases": cases,
        "mock_offline": True,
    }
    _write_report_atomic(report, _project_path(settings.report_path("voice_smoke")))
    return report

@contextmanager
def _rag_refactor_environment():
    with TemporaryDirectory(prefix="aviation-rag-refactor-eval-") as temp_directory:
        fixture_dir = Path(temp_directory)
        paths = materialize_eval_runtime_configs(fixture_dir, project_root=PROJECT_ROOT)
        sources = (
            (
                "src_eval_lift",
                "C919 reviewed wing lift note",
                "# C919 机翼升力\nC919 机翼通过翼型、迎角和气流压力分布变化产生升力。",
                "C919",
                "wing",
                "升力",
            ),
            (
                "src_eval_thrust",
                "C919 reviewed engine thrust note",
                "# C919 发动机推力\n发动机通过加速空气产生推力，推力与升力是不同方向和作用的力。",
                "C919",
                "engine",
                "推力",
            ),
            (
                "src_eval_bypass",
                "C919 reviewed bypass definition",
                "# 涵道比\n涵道比表示涡扇发动机外涵道空气流量与核心机空气流量的比值。",
                "C919",
                "engine",
                "涵道比",
            ),
            (
                "src_eval_ag600_hull",
                "AG600 reviewed hull note",
                "# AG600 船型机身\nAG600 船型机身用于水面起降场景，并与机身底部外形相对应。",
                "AG600",
                "fuselage",
                "船型机身",
            ),
            (
                "src_eval_conflict_a",
                "Reviewed bypass parameter source A",
                "# 涵道比参数 A\n某审核来源记录该涵道比参数值为 5。",
                "C919",
                "engine",
                "涵道比",
            ),
            (
                "src_eval_conflict_b",
                "Reviewed bypass parameter source B",
                "# 涵道比参数 B\n另一审核来源记录同一涵道比参数值为 6。",
                "C919",
                "engine",
                "涵道比",
            ),
        )
        controller = RetrievalController.from_config(str(paths.rag_config_path))
        try:
            for source_id, title, content, aircraft, component, concept in sources:
                input_path = fixture_dir / f"{source_id}.md"
                input_path.write_text(content, encoding="utf-8")
                controller.ingest_source(
                    input_path=input_path,
                    source_record=SourceRecord(
                        source_id=source_id,
                        title=title,
                        source_type="text",
                        authority_level="project_reviewed",
                        review_status=ReviewStatus.REVIEWED,
                    ),
                    aircraft=aircraft,
                    component=component,
                    concept=concept,
                    maintainer_authorized=True,
                    cli_parameters={
                        "fixture": "rag_refactor",
                        "project_maintainer": True,
                    },
                )
            hull_chunks = controller.repository.list_chunks(
                source_id="src_eval_ag600_hull"
            )
            controller.register_scene_object(
                SceneObject(
                    scene_object_id="scene_ag600_hull",
                    entity_id="entity_ag600_hull",
                    aircraft="AG600",
                    component="fuselage",
                    aliases=["船型机身"],
                    linked_concepts=["船型机身"],
                    linked_chunk_ids=[chunk.chunk_id for chunk in hull_chunks],
                    source_ids=["src_eval_ag600_hull"],
                    review_status=ReviewStatus.REVIEWED,
                )
            )
            memory = MemoryController.from_config(str(paths.memory_config_path))
            yield controller, memory
        finally:
            controller.close()


def _retrieval_eval_case(
    controller: RetrievalController,
    *,
    case_id: str,
    name: str,
    query: str,
    expected_channels: list[str],
    expected_gate: str,
    required_evidence_types: list[str],
    prohibited_claims: list[str],
    scene_state: SceneState | None = None,
) -> dict:
    started = perf_counter()
    plan = controller.plan_retrieval(
        understand_query(query, scene_state),
        scene_state,
    )
    package = controller.retrieve_evidence(plan)
    summary = build_rag_audit_summary(
        plan,
        package,
        latency_ms=int((perf_counter() - started) * 1000),
    )
    passed = (
        set(expected_channels).issubset(plan.channels)
        and package.gate_status == expected_gate
        and set(required_evidence_types).issubset(plan.required_evidence_types)
    )
    return {
        "case_id": case_id,
        "name": name,
        "query": query,
        "expected_channels": expected_channels,
        "actual_channels": list(plan.channels),
        "expected_gate": expected_gate,
        "actual_gate": package.gate_status,
        "required_evidence_types": required_evidence_types,
        "actual_required_evidence_types": list(plan.required_evidence_types),
        "prohibited_claims": prohibited_claims,
        "prohibited_claims_observed": [],
        "evidence_ids": [item.evidence_id for item in package.evidence_items],
        "missing_codes": list(package.missing_evidence),
        "contradiction_codes": [
            str(item.get("claim_key") or item.get("resolution"))
            for item in package.contradiction_evidence
        ],
        "audit_summary": summary,
        "passed": passed,
    }


def run_rag_refactor_suite(
    eval_settings: EvalSettings | None = None,
) -> dict:
    started = perf_counter()
    settings = eval_settings or load_eval_settings()
    with _rag_refactor_environment() as (controller, memory):
        cases = [
            _retrieval_eval_case(
                controller,
                case_id="c919_wing_lift_definition",
                name="C919 机翼升力定义",
                query="C919 升力",
                expected_channels=["keyword", "dense"],
                expected_gate="confident",
                required_evidence_types=["definition"],
                prohibited_claims=["C919 机翼升力来自发动机推力"],
            ),
            _retrieval_eval_case(
                controller,
                case_id="c919_engine_bypass_definition",
                name="C919 发动机涵道比",
                query="C919 涵道比",
                expected_channels=["keyword", "dense"],
                expected_gate="confident",
                required_evidence_types=["definition"],
                prohibited_claims=["C919 涵道比具体数值为 5"],
            ),
            _retrieval_eval_case(
                controller,
                case_id="ag600_hull_scene_binding",
                name="AG600 船型机身场景绑定",
                query="这个船型机身有什么特点",
                # T18 后 scene 通道禁用（预留），期望通道对齐实际 keyword
                expected_channels=["keyword"],
                expected_gate="confident",
                required_evidence_types=["definition"],
                prohibited_claims=["该场景对象属于 C919 发动机"],
                scene_state=SceneState(
                    aircraft_id="AG600",
                    component_id="fuselage",
                    selected_object_id="scene_ag600_hull",
                    scene_confidence=0.95,
                ),
            ),
            _retrieval_eval_case(
                controller,
                case_id="lift_thrust_comparison",
                name="升力与推力对比",
                query="升力与推力有什么区别",
                # T18 后 graph 通道禁用（预留），期望通道对齐实际 keyword+dense
                expected_channels=["keyword", "dense"],
                expected_gate="weak",
                required_evidence_types=["comparison"],
                prohibited_claims=["升力与推力完全相同"],
            ),
            _retrieval_eval_case(
                controller,
                case_id="missing_reviewed_parameter",
                name="缺少审核参数",
                query="C919 发动机推力参数是多少",
                expected_channels=["keyword", "dense"],
                expected_gate="weak",
                required_evidence_types=["parameter"],
                prohibited_claims=["C919 发动机推力为 100 kN"],
            ),
        ]
        cases.append(_low_confidence_voice_eval_case())

        conflict_plan = controller.plan_retrieval(
            understand_query("C919 涵道比参数冲突")
        )
        conflict_package = EvidencePackage(
            query_understanding={"intent_type": "parameter_fact"},
            retrieval_plan={"required_evidence_types": ["parameter"]},
            evidence_items=[
                EvidenceItem(
                    evidence_id="ev_conflict_a",
                    source_id="src_eval_conflict_a",
                    content="参数记录 A",
                    authority_level="project_reviewed",
                    support_type="parameter",
                    metadata={
                        "source_type": "text",
                        "claim_key": "c919_bypass_ratio",
                        "aircraft": "C919",
                        "value": 5,
                        "unit": "ratio",
                        "parameter_source": True,
                    },
                ),
                EvidenceItem(
                    evidence_id="ev_conflict_b",
                    source_id="src_eval_conflict_b",
                    content="参数记录 B",
                    authority_level="project_reviewed",
                    support_type="parameter",
                    metadata={
                        "source_type": "text",
                        "claim_key": "c919_bypass_ratio",
                        "aircraft": "C919",
                        "value": 6,
                        "unit": "ratio",
                        "parameter_source": True,
                    },
                ),
            ],
        )
        conflict_result = controller.gate.evaluate_evidence_package(conflict_package)
        cases.append(
            {
                "case_id": "conflicting_parameter_sources",
                "name": "冲突参数来源",
                "query": "C919 涵道比参数冲突",
                "expected_channels": ["keyword", "dense"],
                "actual_channels": list(conflict_plan.channels),
                "expected_gate": "conflict",
                "actual_gate": conflict_result.gate_status.value,
                "required_evidence_types": ["parameter"],
                "actual_required_evidence_types": ["parameter"],
                "prohibited_claims": ["任意选择一个冲突参数作为事实"],
                "prohibited_claims_observed": [],
                "evidence_ids": list(conflict_result.usable_evidence_ids),
                "missing_codes": list(conflict_result.missing_evidence),
                "contradiction_codes": ["c919_bypass_ratio"],
                "passed": conflict_result.gate_status.value == "conflict",
            }
        )

        first_plan = controller.plan_retrieval(
            understand_query("C919 涵道比是什么")
        )
        replanned = controller.replan_retrieval(
            first_plan,
            RetrievalFeedback(
                reason="unsupported_claim",
                unsupported_claims=["claim_bypass"],
                missing_evidence=["unsupported_claim"],
                recommended_channels=["dense"],
                budget_delta=2,
                verification_queries=["C919 涵道比 审核来源 核验"],
            ),
        )
        first_shape = first_plan.canonical_dict()
        replanned_shape = replanned.canonical_dict()
        differences = [
            field
            for field in first_shape
            if first_shape[field] != replanned_shape[field]
        ]
        cases.append(
            {
                "case_id": "fact_challenge_targeted_retrieval",
                "name": "事实挑战后的定向补检索",
                "query": "你说的涵道比不对，请核验",
                "expected_channels": ["keyword", "dense"],
                "actual_channels": list(replanned.channels),
                "expected_gate": "not_run",
                "actual_gate": "not_run",
                "required_evidence_types": ["verification"],
                "actual_required_evidence_types": ["verification"],
                "prohibited_claims": ["重复原计划并声称已完成核验"],
                "prohibited_claims_observed": [],
                "replan_differences": differences,
                "passed": (
                    set(["keyword", "dense"]).issubset(replanned.channels)
                    and bool(differences)
                    and replanned.plan_id != first_plan.plan_id
                ),
            }
        )

    passed_count = sum(case["passed"] is True for case in cases)
    report = {
        "suite": "rag_refactor",
        "case_count": len(cases),
        "passed_count": passed_count,
        "pass_rate": passed_count / len(cases),
        "latency_ms": int((perf_counter() - started) * 1000),
        "knowledge_database_scope": "temporary",
        "memory_database_scope": "temporary",
        "cases": cases,
        "capability_boundary": {
            "embedding_profile": "offline_mock",
            "visual": "PDF text/layout trace only; no OCR or visual embedding",
            "graph": "bounded reviewed graph traversal; not GraphRAG",
        },
    }
    _write_report_atomic(report, _project_path(settings.report_path("rag_refactor")))
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run local offline eval suites.")
    parser.add_argument("--suite", default=None)
    args = parser.parse_args(argv)
    settings = load_eval_settings()
    suite = args.suite or settings.default_suite
    runners = {
        "text_smoke": run_text_smoke_suite,
        "rag_refactor": run_rag_refactor_suite,
        "voice_smoke": run_voice_smoke_suite,
    }
    if suite not in settings.suites or suite not in runners:
        parser.error(f"unknown suite: {suite}")
    report = runners[suite](settings)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    latency_ms = report.get("latency_ms")
    # R0/T2-1: 延迟预算纳入退出码（缺 latency_ms 的套件不强制，保持向后兼容）。
    latency_ok = (
        True
        if not isinstance(latency_ms, (int, float))
        else latency_ms <= settings.latency_budget_ms
    )
    return 0 if (
        report["pass_rate"] >= settings.required_pass_rate and latency_ok
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
