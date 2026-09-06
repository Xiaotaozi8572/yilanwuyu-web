# Task 1 Review Package

Git diff unavailable for this task because parent repo is in rebase and the project tree is mostly untracked. This package contains the full current contents of the files owned by Task 1.

## src/prompts/asset_models.py

```python
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from core.contracts import BaseContract
from services.model_client import ModelMessage


class PromptStatus(StrEnum):
    DRAFT = "draft"
    CANDIDATE = "candidate"
    ACTIVE = "active"
    EXPERIMENTAL = "experimental"
    DEPRECATED = "deprecated"


@dataclass
class PromptVariableSpec(BaseContract):
    required_fields = ("name", "source", "value_type", "missing_behavior")

    name: str = ""
    source: str = ""
    value_type: str = "text"
    required: bool = False
    missing_behavior: str = "omit"
    description: str = ""


@dataclass
class PromptContentVersion(BaseContract):
    required_fields = ("template_id", "version", "content", "review_status")

    template_id: str = ""
    version: str = ""
    content: str = ""
    parent_version: str | None = None
    change_reason: str = ""
    created_at: str = ""
    review_status: PromptStatus = PromptStatus.DRAFT
    snapshot_id: str | None = None
    variables: list[PromptVariableSpec] = field(default_factory=list)


@dataclass
class TeachingPromptAsset(BaseContract):
    required_fields = ("template_id", "title", "task_type", "status", "active_version")

    template_id: str = ""
    title: str = ""
    task_type: str = ""
    concept_scope: list[str] = field(default_factory=list)
    scene_scope: list[str] = field(default_factory=list)
    status: PromptStatus = PromptStatus.DRAFT
    active_version: str = ""
    activation_scope: list[str] = field(default_factory=list)
    constraints: list[str] = field(default_factory=list)
    risk_boundaries: list[str] = field(default_factory=list)


@dataclass
class PromptSelection(BaseContract):
    template_id: str = ""
    version: str = ""
    task_type: str = ""
    status: PromptStatus = PromptStatus.ACTIVE
    route_reason: str = ""
    missing_variables: list[str] = field(default_factory=list)
    selected_asset: TeachingPromptAsset | None = None
    selected_version: PromptContentVersion | None = None
    is_clarification: bool = False


@dataclass
class PromptMessageBundle(BaseContract):
    messages: list[ModelMessage] = field(default_factory=list)
    injection_order: list[str] = field(default_factory=list)
    template_id: str = ""
    version: str = ""
    snapshot_id: str | None = None
    injection_summary: list[dict[str, Any]] = field(default_factory=list)
    missing_variables: list[str] = field(default_factory=list)
    is_final_fact_prompt: bool = True


@dataclass
class PromptOptimizationRecord(BaseContract):
    input_version: str = ""
    output_draft_version: str = ""
    model: str = ""
    instruction: str = ""
    trigger_event: str = ""
    created_at: str = ""


@dataclass
class PromptExample(BaseContract):
    variables: dict[str, Any] = field(default_factory=dict)
    rag_requirement: str = ""
    expected_focus: list[str] = field(default_factory=list)
    failure_modes: list[str] = field(default_factory=list)
    real_output: str = ""
    score: dict[str, float] = field(default_factory=dict)


@dataclass
class PromptEvaluationSnapshot(BaseContract):
    required_fields = ("snapshot_id", "template_id", "version", "final_decision")

    snapshot_id: str = ""
    template_id: str = ""
    version: str = ""
    test_cases: list[str] = field(default_factory=list)
    dimension_scores: dict[str, float] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)
    improvements: list[str] = field(default_factory=list)
    final_decision: str = ""


@dataclass
class CompareStopSignals(BaseContract):
    target_vs_baseline: str = ""
    reference_gap: str = ""
    overfit_risk: str = "unknown"
    stop_recommendation: str = ""


@dataclass
class PromptGovernanceResult(BaseContract):
    template_id: str = ""
    version: str = ""
    action: str = ""
    ok: bool = False
    reason: str = ""
```

## src/core/contracts.py

```python
from __future__ import annotations

from dataclasses import asdict, dataclass, field, fields
from datetime import UTC, datetime
from typing import Any, ClassVar
from uuid import uuid4

from core.actions import ActionDecision
from core.errors import ContractValidationError


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


def utc_now_iso() -> str:
    return datetime.now(UTC).isoformat()


@dataclass
class BaseContract:
    required_fields: ClassVar[tuple[str, ...]] = ()

    def __post_init__(self) -> None:
        self.validate()

    def validate(self) -> None:
        for name in self.required_fields:
            value = getattr(self, name, None)
            if value is None or value == "" or value == [] or value == {}:
                raise ContractValidationError(name, "required field is empty")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "BaseContract":
        allowed = {item.name for item in fields(cls)}
        return cls(**{key: value for key, value in payload.items() if key in allowed})


@dataclass
class SceneState(BaseContract):
    scene_state_id: str = field(default_factory=lambda: new_id("scene"))
    aircraft_id: str | None = None
    component_id: str | None = None
    hotspot_label: str | None = None
    camera_view: str | None = None
    visual_refs: list[str] = field(default_factory=list)
    scene_confidence: float = 0.0
    selected_object_id: str | None = None
    candidate_object_ids: list[str] = field(default_factory=list)


@dataclass
class MemoryContext(BaseContract):
    memory_context_id: str = field(default_factory=lambda: new_id("memctx"))
    learner_profile: dict[str, Any] = field(default_factory=dict)
    dialogue_context: list[str] = field(default_factory=list)
    session_preference: dict[str, Any] = field(default_factory=dict)
    misconception_record: list[dict[str, Any]] = field(default_factory=list)
    memory_boundary: list[str] = field(default_factory=list)
    prohibited_memories: list[str] = field(default_factory=list)
    temporal_notes: list[str] = field(default_factory=list)


@dataclass
class EvidenceItem(BaseContract):
    required_fields: ClassVar[tuple[str, ...]] = ("evidence_id", "source_id", "content")

    evidence_id: str
    source_id: str
    content: str
    review_status: str = "reviewed"
    authority_level: str = "standard"
    metadata: dict[str, Any] = field(default_factory=dict)
    usable_as_core_evidence: bool = True


@dataclass
class EvidencePackage(BaseContract):
    evidence_package_id: str = field(default_factory=lambda: new_id("evidence_pkg"))
    query_understanding: dict[str, Any] = field(default_factory=dict)
    scene_binding: dict[str, Any] = field(default_factory=dict)
    retrieval_plan: dict[str, Any] = field(default_factory=dict)
    evidence_items: list[EvidenceItem] = field(default_factory=list)
    claim_support_map: dict[str, Any] = field(default_factory=dict)
    missing_evidence: list[str] = field(default_factory=list)
    generation_boundary: list[str] = field(default_factory=list)
    audit_trace: list[dict[str, Any]] = field(default_factory=list)
    gate_status: str = "unclear"


@dataclass
class AnswerEnvelope(BaseContract):
    answer_id: str = field(default_factory=lambda: new_id("answer"))
    answer_type: str = "clarification"
    short_answer: str = ""
    main_answer: str = ""
    evidence_refs: list[str] = field(default_factory=list)
    source_binding: dict[str, list[str]] = field(default_factory=dict)
    visual_refs: list[str] = field(default_factory=list)
    uncertainty_notes: list[str] = field(default_factory=list)
    safety_notes: list[str] = field(default_factory=list)
    follow_up_questions: list[str] = field(default_factory=list)
    claim_candidates: list[dict[str, Any]] = field(default_factory=list)
    display_blocks: list[dict[str, Any]] = field(default_factory=list)
    generation_trace: dict[str, Any] = field(default_factory=dict)


@dataclass
class CheckReport(BaseContract):
    check_report_id: str = field(default_factory=lambda: new_id("check"))
    score_card: dict[str, float] = field(default_factory=dict)
    issue_list: list[dict[str, Any]] = field(default_factory=list)
    failed_checks: list[str] = field(default_factory=list)
    action_decision: ActionDecision = ActionDecision.HUMAN_REVIEW
    revised_instruction: str | None = None
    audit_log: list[dict[str, Any]] = field(default_factory=list)
    loop_count: int = 0


@dataclass
class RewritePlan(BaseContract):
    rewrite_plan_id: str = field(default_factory=lambda: new_id("rewrite"))
    feedback_intent: str = ""
    rewrite_actions: list[str] = field(default_factory=list)
    preserved_source_binding: dict[str, list[str]] = field(default_factory=dict)
    delta_map: dict[str, Any] = field(default_factory=dict)
    memory_update_candidate: dict[str, Any] | None = None
    rewrite_log: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class VoiceTurnEvent(BaseContract):
    voice_turn_event_id: str = field(default_factory=lambda: new_id("voice_turn"))
    session_id: str = ""
    raw_transcript: str = ""
    normalized_query: str = ""
    asr_confidence: float = 0.0
    needs_clarification: bool = False
    scene_object_id: str | None = None


@dataclass
class FeedbackEvent(BaseContract):
    feedback_event_id: str = field(default_factory=lambda: new_id("feedback"))
    run_id: str = ""
    feedback_text: str = ""
    feedback_intent: str = ""
    source: str = "text"


@dataclass
class RunTrace(BaseContract):
    required_fields: ClassVar[tuple[str, ...]] = ("run_id", "current_state", "created_at")

    run_id: str = field(default_factory=lambda: new_id("run"))
    current_state: str = "INPUT_RECEIVED"
    created_at: str = field(default_factory=utc_now_iso)
    updated_at: str = field(default_factory=utc_now_iso)
    user_query: str | None = None
    scene_state_id: str | None = None
    prompt_template_id: str | None = None
    memory_context_ids: list[str] = field(default_factory=list)
    retrieval_plan: dict[str, Any] = field(default_factory=dict)
    evidence_ids: list[str] = field(default_factory=list)
    answer_id: str | None = None
    check_report_id: str | None = None
    action_decision: str | None = None
    rewrite_log_id: str | None = None
    final_answer_id: str | None = None
    latency_ms: int = 0
    transitions: list[dict[str, Any]] = field(default_factory=list)
    errors: list[dict[str, Any]] = field(default_factory=list)
    audit_events: list[dict[str, Any]] = field(default_factory=list)


CONTRACT_TYPES: dict[str, type[BaseContract]] = {
    "scene_state": SceneState,
    "memory_context": MemoryContext,
    "evidence_package": EvidencePackage,
    "answer_envelope": AnswerEnvelope,
    "check_report": CheckReport,
    "rewrite_plan": RewritePlan,
    "voice_turn_event": VoiceTurnEvent,
    "feedback_event": FeedbackEvent,
    "run_trace": RunTrace,
}


def validate_contract(payload: dict[str, Any], contract_type: str) -> BaseContract:
    try:
        contract_cls = CONTRACT_TYPES[contract_type]
    except KeyError as exc:
        raise ContractValidationError("contract_type", f"unknown contract type: {contract_type}") from exc
    return contract_cls.from_dict(payload)
```

## tests/unit/prompts/test_prompt_models.py

```python
from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptMessageBundle,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)
from services.model_client import ModelMessage


def test_prompt_variable_spec_requires_name_source_and_missing_behavior():
    spec = PromptVariableSpec(
        name="rag_evidence",
        source="evidence_package",
        value_type="evidence_summary",
        required=True,
        missing_behavior="clarification",
        description="Reviewed evidence used as the only fact source.",
    )

    assert spec.name == "rag_evidence"
    assert spec.required is True


def test_prompt_message_bundle_uses_model_messages_only():
    bundle = PromptMessageBundle(
        messages=[ModelMessage(role="system", content="facts from evidence only")],
        injection_order=["system_boundary"],
        template_id="aviation_fact_qa",
        version="v1",
        snapshot_id="aviation_fact_qa_v1",
        injection_summary=[{"section": "system_boundary", "chars": 24}],
    )

    assert bundle.messages[0].role == "system"
    assert bundle.template_id == "aviation_fact_qa"


def test_active_asset_points_to_existing_active_version_shape():
    version = PromptContentVersion(
        template_id="aviation_fact_qa",
        version="v1",
        content="Use {{rag_evidence}} only.",
        review_status=PromptStatus.ACTIVE,
        snapshot_id="aviation_fact_qa_v1",
        variables=[
            PromptVariableSpec(
                name="rag_evidence",
                source="evidence_package",
                value_type="evidence_summary",
                required=True,
                missing_behavior="clarification",
                description="Reviewed evidence.",
            )
        ],
    )
    asset = TeachingPromptAsset(
        template_id="aviation_fact_qa",
        title="Aviation factual answer",
        task_type="concept_explanation",
        status=PromptStatus.ACTIVE,
        active_version="v1",
        activation_scope=["concept_explanation"],
        constraints=["facts_from_evidence_only"],
        risk_boundaries=["no_parameter_without_source"],
    )

    assert asset.active_version == version.version
    assert asset.status == PromptStatus.ACTIVE


def test_evaluation_snapshot_has_final_decision():
    snapshot = PromptEvaluationSnapshot(
        snapshot_id="aviation_fact_qa_v1",
        template_id="aviation_fact_qa",
        version="v1",
        test_cases=["basic_concept"],
        dimension_scores={"faithfulness": 1.0},
        violations=[],
        improvements=["clearer evidence wording"],
        final_decision="approve",
    )

    assert snapshot.final_decision == "approve"
```
