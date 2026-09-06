# Task 4 Review Package v2

## src/prompts/runtime.py

```
from __future__ import annotations

import json
from typing import Any

from core.contracts import EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts.asset_models import PromptMessageBundle, PromptSelection
from prompts.assembler import PromptAssembler
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptRouterConfig


class PromptRuntime:
    def __init__(
        self,
        repository: PromptAssetRepository,
        router: PromptRouter,
        assembler: PromptAssembler,
        config: PromptRouterConfig,
    ) -> None:
        self.repository = repository
        self.router = router
        self.assembler = assembler
        self.config = config

    @classmethod
    def from_config(cls, path: str = "configs/prompts.yaml") -> "PromptRuntime":
        repository = PromptAssetRepository.from_config(path)
        config = PromptRouterConfig.from_file(path)
        router = PromptRouter(repository=repository, config=config)
        assembler = PromptAssembler()
        return cls(repository=repository, router=router, assembler=assembler, config=config)

    def prepare_route(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None = None,
        memory_context: MemoryContext | None = None,
        evidence_package: EvidencePackage | None = None,
        output_contract: dict[str, Any] | None = None,
    ) -> PromptSelection:
        return self.router.select_prompt(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=output_contract or {"type": "answer_envelope"},
        )

    def assemble_bundle(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None = None,
        memory_context: MemoryContext | None = None,
        evidence_package: EvidencePackage | None = None,
        output_contract: dict[str, Any] | None = None,
        weak_points: list[str] | None = None,
        selection: PromptSelection | None = None,
    ) -> PromptMessageBundle:
        resolved_output_contract = output_contract or {"type": "answer_envelope"}
        resolved_selection = selection or self.prepare_route(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=resolved_output_contract,
        )
        variables = self._bind_variables(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=resolved_output_contract,
            weak_points=weak_points or [],
            selection=resolved_selection,
        )
        return self.assembler.assemble_messages(resolved_selection, variables, self.config.injection_order)

    def _bind_variables(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None,
        memory_context: MemoryContext | None,
        evidence_package: EvidencePackage | None,
        output_contract: dict[str, Any],
        weak_points: list[str],
        selection: PromptSelection,
    ) -> dict[str, str]:
        explanation_preference = ""
        learner_level = ""
        if memory_context is not None:
            explanation_preference = str(memory_context.session_preference.get("explanation_preference", ""))
            learner_level = str(memory_context.learner_profile.get("level", ""))

        return {
            "task_type": selection.task_type or query_object.intent_type,
            "query": self._format_query(query_object),
            "aircraft": query_object.target_aircraft or "",
            "component": query_object.target_component or "",
            "concept": query_object.target_concept or "",
            "learner_level": learner_level,
            "explanation_preference": explanation_preference,
            "scene_state": self._format_scene_state(scene_state),
            "rag_evidence": self._format_evidence(evidence_package),
            "memory_context": self._format_memory_context(memory_context),
            "weak_points": self._format_weak_points(weak_points),
            "output_contract": self._format_output_contract(output_contract),
        }

    def _format_query(self, query_object: QueryObject) -> str:
        details = [
            f"raw_query={query_object.raw_query}",
            f"normalized_query={query_object.normalized_query}",
            f"intent_type={query_object.intent_type}",
        ]
        if query_object.target_aircraft:
            details.append(f"target_aircraft={query_object.target_aircraft}")
        if query_object.target_component:
            details.append(f"target_component={query_object.target_component}")
        if query_object.target_concept:
            details.append(f"target_concept={query_object.target_concept}")
        if query_object.scene_object_id:
            details.append(f"scene_object_id={query_object.scene_object_id}")
        return "\n".join(details)

    def _format_scene_state(self, scene_state: SceneState | None) -> str:
        if scene_state is None:
            return ""
        details = []
        if scene_state.aircraft_id:
            details.append(f"aircraft_id={scene_state.aircraft_id}")
        if scene_state.component_id:
            details.append(f"component_id={scene_state.component_id}")
        if scene_state.hotspot_label:
            details.append(f"hotspot_label={scene_state.hotspot_label}")
        if scene_state.camera_view:
            details.append(f"camera_view={scene_state.camera_view}")
        if scene_state.selected_object_id:
            details.append(f"selected_object_id={scene_state.selected_object_id}")
        return "\n".join(details)

    def _format_memory_context(self, memory_context: MemoryContext | None) -> str:
        if memory_context is None:
            return ""
        blocks = []
        if memory_context.session_preference:
            blocks.append(
                "session_preference="
                + json.dumps(memory_context.session_preference, ensure_ascii=False, sort_keys=True)
            )
        if memory_context.learner_profile:
            blocks.append(
                "learner_profile=" + json.dumps(memory_context.learner_profile, ensure_ascii=False, sort_keys=True)
            )
        if memory_context.misconception_record:
            blocks.append(
                "misconception_record="
                + json.dumps(memory_context.misconception_record, ensure_ascii=False, sort_keys=True)
            )
        return "\n".join(blocks)

    def _format_evidence(self, evidence_package: EvidencePackage | None) -> str:
        if evidence_package is None or not evidence_package.evidence_items:
            return ""
        return "\n".join(
            f"- [{item.evidence_id}] {item.content}" for item in evidence_package.evidence_items
        )

    def _format_output_contract(self, output_contract: dict[str, Any]) -> str:
        return json.dumps(output_contract, ensure_ascii=False, sort_keys=True)

    def _format_weak_points(self, weak_points: list[str]) -> str:
        if not weak_points:
            return ""
        return "\n".join(f"- {item}" for item in weak_points)
```

## src/agent/runtime.py

```
from __future__ import annotations

from time import perf_counter
from typing import Any

from agent.actions import AgentActionExecutor
from app.api.errors import AppError, ErrorResponse, PipelineExecutionError
from app.api.schemas import TextQueryRequest, TextQueryResponse
from core.actions import ActionDecision
from core.contracts import MemoryContext, RunTrace, SceneState, new_id
from core.state_machine import AgentState, transition
from core.tracing import append_audit_event, append_error
from generation.answer_types import route_answer_type
from generation.evidence_sketch import build_evidence_sketch
from generation.generator import generate_answer
from generation.planner import generate_plan
from input.query_understanding import understand_query
from knowledge.retrieval_controller import RetrievalController
from prompts.runtime import PromptRuntime
from self_check.decision_router import SelfCheckService


class AgentRuntime:
    def __init__(
        self,
        retrieval_controller: RetrievalController | None = None,
        self_check_service: SelfCheckService | None = None,
        action_executor: AgentActionExecutor | None = None,
        prompt_runtime: PromptRuntime | None = None,
        entry_mode: str = "cli",
        trace_enabled: bool = True,
    ) -> None:
        self.retrieval_controller = retrieval_controller or RetrievalController()
        self.self_check_service = self_check_service or SelfCheckService()
        self.action_executor = action_executor or AgentActionExecutor()
        self.prompt_runtime = prompt_runtime or PromptRuntime.from_config()
        self.entry_mode = entry_mode
        self.trace_enabled = trace_enabled

    def run_text_query(self, request: TextQueryRequest) -> TextQueryResponse:
        run_id = request.run_id or new_id("run")
        trace = RunTrace(run_id=run_id, user_query=request.query)
        started = perf_counter()
        try:
            request.validate()
            scene_state = self._build_scene_state(request.scene_state)
            if scene_state is not None:
                trace.scene_state_id = scene_state.scene_state_id
            append_audit_event(trace, "agent_runtime", "received text query", entry_mode=self.entry_mode)

            query_object = understand_query(request.query, scene_state)
            transition(trace, AgentState.PROMPT_ROUTED, reason="query understood and prompt boundary reserved")

            memory_context = MemoryContext()
            trace.memory_context_ids.append(memory_context.memory_context_id)
            transition(trace, AgentState.MEMORY_CONTEXT_READY, reason="empty mock memory context")

            loop_count = 0
            while True:
                if trace.current_state != AgentState.RETRIEVAL_PLANNED.value:
                    transition(trace, AgentState.RETRIEVAL_PLANNED, reason="retrieval plan created")
                retrieval_plan = self.retrieval_controller.plan_retrieval(query_object, scene_state, memory_context)
                trace.retrieval_plan = retrieval_plan.to_dict()

                evidence_package = self.retrieval_controller.retrieve_evidence(retrieval_plan)
                trace.evidence_ids = [item.evidence_id for item in evidence_package.evidence_items]
                transition(trace, AgentState.EVIDENCE_READY, reason=evidence_package.gate_status)

                answer_type = route_answer_type(query_object, evidence_package, scene_state)
                evidence_sketch = build_evidence_sketch(evidence_package, answer_type)
                generation_plan = generate_plan(answer_type, evidence_sketch)
                prompt_selection = self.prompt_runtime.prepare_route(
                    query_object=query_object,
                    scene_state=scene_state,
                    memory_context=memory_context,
                    evidence_package=evidence_package,
                    output_contract={"type": "answer_envelope"},
                )
                prompt_bundle = self.prompt_runtime.assemble_bundle(
                    query_object=query_object,
                    scene_state=scene_state,
                    memory_context=memory_context,
                    evidence_package=evidence_package,
                    output_contract={"type": "answer_envelope"},
                    selection=prompt_selection,
                )
                trace.prompt_template_id = prompt_bundle.template_id
                trace.prompt_version = prompt_bundle.version
                trace.prompt_snapshot_id = prompt_bundle.snapshot_id
                trace.prompt_route_reason = prompt_selection.route_reason
                trace.prompt_missing_variables = list(prompt_bundle.missing_variables)
                trace.prompt_injection_summary = list(prompt_bundle.injection_summary)
                answer = generate_answer(
                    generation_plan,
                    evidence_sketch,
                    memory_context=memory_context,
                    prompt_selection=prompt_selection,
                    prompt_bundle=prompt_bundle,
                    evidence_package=evidence_package,
                    scene_state=scene_state,
                )
                trace.answer_id = answer.answer_id
                transition(trace, AgentState.ANSWER_DRAFTED, reason=generation_plan.reason)

                while True:
                    check_report = self.self_check_service.check(answer, evidence_package, scene_state, memory_context, loop_count=loop_count)
                    trace.check_report_id = check_report.check_report_id
                    transition(trace, AgentState.SELF_CHECKED, action_decision=check_report.action_decision, reason="self check completed")

                    decision = check_report.action_decision
                    if decision in {ActionDecision.PASS, ActionDecision.SAFE_RESPONSE, ActionDecision.ASK_CLARIFICATION, ActionDecision.STOP}:
                        final_answer = self.action_executor.terminal_answer(decision, answer, check_report, evidence_package, scene_state)
                        trace.final_answer_id = final_answer.answer_id
                        transition(trace, AgentState.FINAL_READY, action_decision=decision, reason=f"{decision.value} terminal response ready")
                        trace.latency_ms += int((perf_counter() - started) * 1000)
                        return TextQueryResponse(
                            run_id=run_id,
                            status="ok",
                            answer=final_answer.to_dict(),
                            action_decision=decision.value,
                            trace=trace.to_dict() if self.trace_enabled else {},
                        )

                    loop_count += 1
                    if decision == ActionDecision.REWRITE_ONLY:
                        answer = self.action_executor.rewrite_only(answer, check_report, scene_state)
                        trace.answer_id = answer.answer_id
                        transition(trace, AgentState.ANSWER_DRAFTED, action_decision=decision, reason="rewrite action applied")
                        continue

                    if decision == ActionDecision.RETRIEVE_MORE:
                        transition(trace, AgentState.RETRIEVAL_PLANNED, action_decision=decision, reason="retrieve more action requested")
                        break

                    final_answer = self.action_executor.stop_response(check_report, scene_state)
                    trace.final_answer_id = final_answer.answer_id
                    transition(trace, AgentState.FINAL_READY, action_decision=ActionDecision.STOP, reason="unsupported decision stopped")
                    return TextQueryResponse(
                        run_id=run_id,
                        status="ok",
                        answer=final_answer.to_dict(),
                        action_decision=ActionDecision.STOP.value,
                        trace=trace.to_dict() if self.trace_enabled else {},
                    )
        except AppError as exc:
            return self._error_response(trace, exc.error_code, exc.message)
        except Exception as exc:
            error = PipelineExecutionError()
            append_error(trace, "agent_runtime", error.error_code, type(exc).__name__)
            return self._error_response(trace, error.error_code, error.message)

    def _build_scene_state(self, payload: dict[str, Any] | None) -> SceneState | None:
        if payload is None:
            return None
        return SceneState.from_dict(payload)  # type: ignore[return-value]

    def _error_response(self, trace: RunTrace, error_code: str, message: str) -> TextQueryResponse:
        append_error(trace, "agent_runtime", error_code, message)
        return TextQueryResponse(
            run_id=trace.run_id,
            status="error",
            action_decision=ActionDecision.STOP.value,
            trace=trace.to_dict() if self.trace_enabled else {},
            error=ErrorResponse(error_code=error_code, message=message, run_id=trace.run_id),
        )
```

## src/generation/generator.py

```
from __future__ import annotations

import re
from typing import Any

from core.contracts import AnswerEnvelope, EvidencePackage, MemoryContext, SceneState
from generation.answer_types import AnswerType
from generation.citation_binding import CitationBinder
from generation.display_blocks import DisplayBlockBuilder
from generation.evidence_sketch import EvidenceSketch
from generation.planner import GenerationPlan
from prompts.asset_models import PromptMessageBundle
from safety.policy import contains_unsafe_operational_detail
from services.model_client import ModelClient, ModelOptions
from services.structured_output import ANSWER_ENVELOPE_SCHEMA


class GroundedAnswerGenerator:
    def __init__(
        self,
        model_client: ModelClient | None = None,
        model_options: ModelOptions | None = None,
        use_model: bool = False,
    ) -> None:
        self.model_client = model_client
        self.model_options = model_options
        self.use_model = use_model

    def generate_answer(
        self,
        plan: GenerationPlan,
        evidence_sketch: EvidenceSketch,
        memory_context: MemoryContext | None = None,
        prompt_selection: Any | None = None,
        prompt_bundle: PromptMessageBundle | None = None,
        evidence_package: EvidencePackage | None = None,
        scene_state: SceneState | None = None,
    ) -> AnswerEnvelope:
        model_result = None
        if self.use_model and self.model_client and self.model_options and plan.allow_definitive_answer:
            if prompt_bundle is None:
                raise ValueError("prompt_bundle is required when use_model is true")
            model_result = self.model_client.complete_structured(
                prompt_bundle.messages,
                ANSWER_ENVELOPE_SCHEMA,
                self.model_options,
            )
        if model_result and model_result.ok:
            answer = self._answer_from_model_result(plan, model_result.parsed or {})
        elif not plan.allow_definitive_answer:
            answer = self._build_uncertain_answer(plan, evidence_sketch)
        else:
            answer = self._build_grounded_answer(plan, evidence_sketch, memory_context, scene_state)

        if evidence_package is not None:
            CitationBinder().bind_citations(answer, evidence_package)
        DisplayBlockBuilder().build_display_blocks(answer, scene_state)
        answer.generation_trace = {
            "plan_steps": list(plan.steps),
            "template_id": prompt_bundle.template_id if prompt_bundle else getattr(prompt_selection, "template_id", None),
            "template_version": prompt_bundle.version if prompt_bundle else None,
            "snapshot_id": prompt_bundle.snapshot_id if prompt_bundle else None,
            "route_reason": getattr(prompt_selection, "route_reason", None),
            "prompt_missing_variables": list(prompt_bundle.missing_variables) if prompt_bundle else [],
            "prompt_injection_summary": list(prompt_bundle.injection_summary) if prompt_bundle else [],
            "evidence_ids": list(answer.evidence_refs),
            "uncertainty_count": len(answer.uncertainty_notes),
            "output_length": len(answer.main_answer),
            "model_provider": model_result.provider if model_result else None,
            "model_error_code": model_result.error_code.value if model_result and model_result.error_code else None,
            "fallback_used": bool(model_result and not model_result.ok),
        }
        return answer

    def _answer_from_model_result(self, plan: GenerationPlan, parsed: dict) -> AnswerEnvelope:
        return AnswerEnvelope(
            answer_type=parsed.get("answer_type", plan.answer_type.value),
            short_answer=parsed["short_answer"],
            main_answer=parsed["main_answer"],
            uncertainty_notes=list(parsed.get("uncertainty_notes", [])),
            safety_notes=list(parsed.get("safety_notes", [])),
            follow_up_questions=list(parsed.get("follow_up_questions", [])),
            claim_candidates=list(parsed.get("claim_candidates", [])),
        )

    def _build_uncertain_answer(self, plan: GenerationPlan, evidence_sketch: EvidenceSketch) -> AnswerEnvelope:
        missing = evidence_sketch.missing_points or ["reviewed evidence is not available"]
        return AnswerEnvelope(
            answer_type=AnswerType.CLARIFICATION.value,
            short_answer="褰撳墠璧勬枡涓嶈冻锛屼笉鑳界粰鍑虹‘瀹氫簨瀹炲洖绛斻€?,
            main_answer="宸叉湁璧勬枡鏈鐩栬闂鐨勫叧閿瘉鎹細" + "锛?.join(missing) + "銆傚彲浠ヨˉ鍏呰祫鏂欐垨鎹竴涓洿鍏蜂綋鐨勯棶棰樸€?,
            uncertainty_notes=list(missing),
            follow_up_questions=["鏄惁鍙互鎻愪緵宸插鏍歌祫鏂欐垨鏇存槑纭殑閮ㄤ欢/姒傚康锛?],
            claim_candidates=[],
        )

    def _build_grounded_answer(
        self,
        plan: GenerationPlan,
        evidence_sketch: EvidenceSketch,
        memory_context: MemoryContext | None,
        scene_state: SceneState | None,
    ) -> AnswerEnvelope:
        primary_fact = evidence_sketch.facts[0]
        fact_text = primary_fact["text"]
        expression_prefix = self._expression_prefix(memory_context)
        safety_notes = []
        if plan.safety_mode:
            return self._build_operation_safety_answer(fact_text, plan)
        if plan.answer_type == AnswerType.PARAMETER_FACT and not self._contains_number_or_parameter(fact_text):
            return AnswerEnvelope(
                answer_type=AnswerType.PARAMETER_FACT.value,
                short_answer="璧勬枡娌℃湁瑕嗙洊鍙紩鐢ㄧ殑鍏蜂綋鍙傛暟銆?,
                main_answer="宸插鏍歌祫鏂欐病鏈夌粰鍑鸿闂鎵€闇€鐨勫叿浣撴暟鍊硷紝鍥犳涓嶈兘琛ュ厖鍙傛暟銆?,
                uncertainty_notes=["parameter_value_missing"],
                claim_candidates=[],
            )
        scene_phrase = ""
        if plan.answer_type == AnswerType.COMPONENT_SCENE and scene_state and scene_state.component_id:
            scene_phrase = f"褰撳墠鍦烘櫙瀵硅薄 `{scene_state.component_id}`锛?
        claim_id = "claim_1"
        return AnswerEnvelope(
            answer_type=plan.answer_type.value,
            short_answer=f"{scene_phrase}{expression_prefix}{fact_text}",
            main_answer=f"{scene_phrase}{expression_prefix}{fact_text}",
            safety_notes=safety_notes,
            claim_candidates=[
                {
                    "claim_id": claim_id,
                    "text": fact_text,
                    "claim_type": plan.answer_type.value,
                    "risk_level": "normal" if not plan.safety_mode else "safety_sensitive",
                    "evidence_ids": [primary_fact["evidence_id"]],
                }
            ],
        )

    def _build_operation_safety_answer(self, fact_text: str, plan: GenerationPlan) -> AnswerEnvelope:
        safe_context = "" if contains_unsafe_operational_detail(fact_text) else f"鐩稿叧璧勬枡鍙敤浜庣悊瑙ｈ儗鏅細{fact_text}"
        main_answer = (
            "鎴戜笉鑳芥彁渚涘叿浣撶淮淇€佹媶鍗搞€佹敼瑁呫€佹晠闅滃缃垨椋炶鎿嶄綔姝ラ銆?
            "鍙互浠庤埅绌虹鏅搴﹁В閲婄浉鍏冲師鐞嗐€佸畨鍏ㄨ竟鐣屽拰涓轰粈涔堣繖绫绘搷浣滈渶瑕佺敱鍏峰璧勮川鐨勪汉鍛樻寜姝ｅ紡鎵嬪唽鎵ц銆?
        )
        if safe_context:
            main_answer += safe_context
        return AnswerEnvelope(
            answer_type=plan.answer_type.value,
            short_answer="涓嶈兘鎻愪緵鍙墽琛屽嵄闄╂搷浣滄楠ゃ€?,
            main_answer=main_answer,
            safety_notes=["浠呮彁渚涜埅绌虹鏅В閲婏紝涓嶆彁渚涚淮淇€佹敼瑁呫€侀琛屾搷浣滄垨鏁呴殰澶勭疆姝ラ銆?],
            follow_up_questions=["鏄惁鏀逛负瑙ｉ噴鐩稿叧鑸┖鍘熺悊鎴栧畨鍏ㄨ竟鐣岋紵"],
            claim_candidates=[],
        )

    def _expression_prefix(self, memory_context: MemoryContext | None) -> str:
        if not memory_context:
            return ""
        preference = memory_context.session_preference.get("explanation_preference")
        if preference in {"plain", "use analogy", "simple"}:
            return "绠€鍗曟潵璇达紝"
        return ""

    def _contains_number_or_parameter(self, text: str) -> bool:
        return bool(re.search(r"\d", text))


def generate_answer(
    plan: GenerationPlan,
    evidence_sketch: EvidenceSketch,
    memory_context: MemoryContext | None = None,
    prompt_selection: Any | None = None,
    prompt_bundle: PromptMessageBundle | None = None,
    evidence_package: EvidencePackage | None = None,
    scene_state: SceneState | None = None,
    model_client: ModelClient | None = None,
    model_options: ModelOptions | None = None,
    use_model: bool = False,
) -> AnswerEnvelope:
    return GroundedAnswerGenerator(model_client=model_client, model_options=model_options, use_model=use_model).generate_answer(
        plan,
        evidence_sketch,
        memory_context,
        prompt_selection,
        prompt_bundle,
        evidence_package,
        scene_state,
    )
```

## src/core/contracts.py

```
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
    prompt_version: str | None = None
    prompt_snapshot_id: str | None = None
    prompt_route_reason: str | None = None
    prompt_missing_variables: list[str] = field(default_factory=list)
    prompt_injection_summary: list[dict[str, Any]] = field(default_factory=list)
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

## tests/unit/prompts/test_prompt_runtime.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts import PromptAssetRepository, PromptAssembler, PromptRouter, PromptRuntime


def test_prompt_runtime_from_config_builds_repository_router_and_assembler():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")

    assert isinstance(runtime.repository, PromptAssetRepository)
    assert isinstance(runtime.router, PromptRouter)
    assert isinstance(runtime.assembler, PromptAssembler)
    assert runtime.config.default_template_id == "aviation_fact_qa"


def test_prepare_route_returns_prompt_selection():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )

    selection = runtime.prepare_route(query_object=query, scene_state=SceneState())

    assert selection.template_id == "aviation_basic_safe"
    assert selection.is_clarification is True
    assert selection.route_reason == "missing_required_variables"


def test_prepare_route_selects_non_rag_prompt_when_version_contract_allows_it():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Please make it short enough to speak",
        normalized_query="Please make it short enough to speak",
        intent_type="spoken_answer_style",
    )

    selection = runtime.prepare_route(query_object=query)

    assert selection.template_id == "spoken_answer_style_prompt"
    assert selection.route_reason == "intent_scope_match"
    assert selection.is_clarification is False


def test_assemble_bundle_binds_runtime_variables_without_context_role_or_prompt_leak_in_summary():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
        target_component="wing",
        target_concept="lift",
    )
    evidence = EvidencePackage(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_lift",
                source_id="src_lift",
                content="Lift depends on airflow across the wing.",
            )
        ]
    )

    bundle = runtime.assemble_bundle(
        query_object=query,
        scene_state=SceneState(component_id="wing", selected_object_id="wing_001"),
        memory_context=MemoryContext(
            learner_profile={"level": "beginner"},
            session_preference={"explanation_preference": "plain"},
        ),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
        weak_points=["confuses lift and thrust"],
    )

    assert bundle.template_id == "aviation_fact_qa"
    assert bundle.is_final_fact_prompt is True
    assert len(bundle.messages) == 2
    assert all(message.role != "context" for message in bundle.messages)
    rendered_text = "\n".join(message.content for message in bundle.messages)
    assert "{{rag_evidence}}" not in rendered_text
    assert "wing_001" in rendered_text
    assert "confuses lift and thrust" in rendered_text
    assert "Lift depends on airflow across the wing." in rendered_text
    assert all("aviation science tutor" not in str(item).lower() for item in bundle.injection_summary)


def test_assemble_bundle_reuses_prepared_selection_without_rerouting():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )
    selection = runtime.prepare_route(query_object=query)

    def fail_prepare_route(**_: object) -> object:
        raise AssertionError("prepare_route should not be called when selection is provided")

    runtime.prepare_route = fail_prepare_route  # type: ignore[method-assign]

    bundle = runtime.assemble_bundle(
        query_object=query,
        output_contract={"type": "answer_envelope"},
        selection=selection,
    )

    assert bundle.template_id == selection.template_id
    assert bundle.version == selection.version
```

## tests/unit/generation/test_model_generation.py

```
from core.contracts import EvidenceItem, EvidencePackage
from generation.answer_types import route_answer_type
from generation.evidence_sketch import build_evidence_sketch
from generation.generator import generate_answer
from generation.planner import generate_plan
from input.query_understanding import understand_query
from prompts.runtime import PromptRuntime
from services.model_client import MockModelClient, ModelMessage, ModelOptions


def _options() -> ModelOptions:
    return ModelOptions(model_alias="deepseek-flash", timeout_seconds=1, max_retries=0, temperature=0.2)


def _plan_and_sketch() -> tuple:
    query = understand_query("Explain lift")
    package = EvidencePackage(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_lift",
                source_id="src_lift",
                content="Lift comes from pressure differences across the wing.",
            )
        ],
        gate_status="confident",
    )
    answer_type = route_answer_type(query, package)
    sketch = build_evidence_sketch(package, answer_type)
    return query, package, generate_plan(answer_type, sketch), sketch


def _prompt_materials(query, package):
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    selection = runtime.prepare_route(
        query_object=query,
        evidence_package=package,
        output_contract={"type": "answer_envelope"},
    )
    bundle = runtime.assemble_bundle(
        query_object=query,
        evidence_package=package,
        output_contract={"type": "answer_envelope"},
    )
    return selection, bundle


class RecordingModelClient:
    def __init__(self, response: dict[str, object]) -> None:
        self.response = response
        self.seen_messages: list[ModelMessage] | None = None

    def complete_structured(self, messages, schema, options):
        self.seen_messages = list(messages)
        return MockModelClient(self.response, provider="recording").complete_structured(messages, schema, options)


def test_generation_can_use_model_client_structured_output():
    query, package, plan, sketch = _plan_and_sketch()
    selection, bundle = _prompt_materials(query, package)
    client = MockModelClient(
        {
            "answer_type": "concept_explanation",
            "short_answer": "Lift comes from pressure differences.",
            "main_answer": "Lift comes from pressure differences.",
            "claim_candidates": [
                {
                    "claim_id": "claim_1",
                    "text": "Lift comes from pressure differences.",
                    "evidence_ids": ["ev_lift"],
                }
            ],
        }
    )

    answer = generate_answer(
        plan,
        sketch,
        prompt_selection=selection,
        prompt_bundle=bundle,
        evidence_package=package,
        model_client=client,
        model_options=_options(),
        use_model=True,
    )

    assert answer.main_answer == "Lift comes from pressure differences."
    assert answer.source_binding == {"claim_1": ["ev_lift"]}
    assert answer.generation_trace["model_provider"] == "mock"
    assert answer.generation_trace["fallback_used"] is False
    assert answer.generation_trace["template_id"] == "aviation_fact_qa"
    assert answer.generation_trace["template_version"] == "v2"
    assert answer.generation_trace["snapshot_id"] == bundle.snapshot_id
    assert answer.generation_trace["route_reason"] == selection.route_reason
    assert answer.generation_trace["prompt_missing_variables"] == []
    assert isinstance(answer.generation_trace["prompt_injection_summary"], list)


def test_generation_rule_path_still_works_without_prompt_bundle_when_model_disabled():
    _query, package, plan, sketch = _plan_and_sketch()

    answer = generate_answer(
        plan,
        sketch,
        evidence_package=package,
        use_model=False,
    )

    assert "pressure differences across the wing" in answer.main_answer
    assert answer.source_binding == {"claim_1": ["ev_lift"]}
    assert answer.generation_trace["template_id"] is None
    assert answer.generation_trace["template_version"] is None
    assert answer.generation_trace["prompt_missing_variables"] == []


def test_generation_sends_exact_prompt_bundle_messages_to_model_client():
    query, package, plan, sketch = _plan_and_sketch()
    selection, bundle = _prompt_materials(query, package)
    client = RecordingModelClient(
        {
            "answer_type": "concept_explanation",
            "short_answer": "Lift comes from pressure differences.",
            "main_answer": "Lift comes from pressure differences.",
            "claim_candidates": [],
        }
    )

    answer = generate_answer(
        plan,
        sketch,
        prompt_selection=selection,
        prompt_bundle=bundle,
        evidence_package=package,
        model_client=client,
        model_options=_options(),
        use_model=True,
    )

    assert client.seen_messages == bundle.messages
    assert answer.generation_trace["model_provider"] == "recording"


def test_generation_falls_back_to_rule_generator_when_model_output_invalid():
    query, package, plan, sketch = _plan_and_sketch()
    selection, bundle = _prompt_materials(query, package)
    client = MockModelClient({"short_answer": "missing main_answer"})

    answer = generate_answer(
        plan,
        sketch,
        prompt_selection=selection,
        prompt_bundle=bundle,
        evidence_package=package,
        model_client=client,
        model_options=_options(),
        use_model=True,
    )

    assert "pressure differences across the wing" in answer.main_answer
    assert answer.generation_trace["model_error_code"] == "structured_output_invalid"
    assert answer.generation_trace["fallback_used"] is True


def test_generation_requires_prompt_bundle_for_model_calls():
    _query, package, plan, sketch = _plan_and_sketch()
    client = MockModelClient()

    try:
        generate_answer(
            plan,
            sketch,
            evidence_package=package,
            model_client=client,
            model_options=_options(),
            use_model=True,
        )
    except ValueError as exc:
        assert str(exc) == "prompt_bundle is required when use_model is true"
    else:
        raise AssertionError("expected ValueError when prompt bundle is missing")
```

## tests/integration/app_loop/test_cli_pipeline.py

```
import json
import os
import subprocess
import sys
from pathlib import Path

from app.api.schemas import TextQueryRequest
from services.app_pipeline import AppPipeline
from knowledge.ingestion.text_ingestor import TextIngestor
from knowledge.retrieval_controller import RetrievalController
from knowledge.schemas import ReviewStatus, SourceRecord


def _seeded_pipeline() -> AppPipeline:
    controller = RetrievalController()
    source = SourceRecord(
        title="Reviewed lift note",
        review_status=ReviewStatus.REVIEWED,
        authority_level="project_reviewed",
    )
    controller.source_registry.register_source(source)
    chunks = TextIngestor(controller.source_registry).ingest_text(
        source.source_id,
        "鏈虹考閫氳繃缈煎瀷鍜岃繋瑙掓敼鍙樺懆鍥存皵娴佸帇鍔涘垎甯冿紝浠庤€屼骇鐢熷崌鍔涖€?,
        aircraft="C919",
        component="wing",
        concept="鍗囧姏",
    )
    controller.add_chunks(chunks)
    return AppPipeline(retrieval_controller=controller)


def test_health_check_is_lightweight():
    health = AppPipeline().health_check()

    assert health.to_dict() == {"status": "ok", "entry_mode": "cli", "trace_enabled": True}


def test_pipeline_returns_answer_run_id_and_trace():
    response = _seeded_pipeline().run_text_query(
        TextQueryRequest(
            query="瑙ｉ噴涓€涓嬫満缈煎崌鍔?,
            scene_state={"aircraft_id": "C919", "component_id": "wing"},
            run_id="run_app_smoke",
        )
    )

    payload = response.to_dict()
    assert payload["status"] == "ok"
    assert payload["run_id"] == "run_app_smoke"
    assert payload["answer"]["main_answer"]
    assert payload["action_decision"] == "PASS"
    assert payload["trace"]["final_answer_id"] == payload["answer"]["answer_id"]
    assert payload["trace"]["prompt_template_id"]
    assert payload["trace"]["prompt_version"]
    assert payload["trace"]["prompt_snapshot_id"]
    assert payload["trace"]["prompt_route_reason"]
    assert payload["trace"]["prompt_missing_variables"] == []
    assert isinstance(payload["trace"]["prompt_injection_summary"], list)
    assert payload["answer"]["generation_trace"]["template_id"] == payload["trace"]["prompt_template_id"]
    assert payload["answer"]["generation_trace"]["template_version"] == payload["trace"]["prompt_version"]
    assert payload["answer"]["generation_trace"]["snapshot_id"] == payload["trace"]["prompt_snapshot_id"]
    assert payload["answer"]["generation_trace"]["route_reason"] == payload["trace"]["prompt_route_reason"]
    assert payload["answer"]["generation_trace"]["prompt_missing_variables"] == payload["trace"]["prompt_missing_variables"]
    assert payload["answer"]["generation_trace"]["prompt_injection_summary"] == payload["trace"]["prompt_injection_summary"]
    assert payload["error"] is None


def test_pipeline_wraps_invalid_request_as_structured_error():
    response = AppPipeline().run_text_query(TextQueryRequest(query="", run_id="run_bad_request"))

    payload = response.to_dict()
    assert payload["status"] == "error"
    assert payload["action_decision"] == "STOP"
    assert payload["error"] == {
        "error_code": "invalid_request",
        "message": "query is required",
        "run_id": "run_bad_request",
    }


def test_cli_smoke_outputs_json_without_http_dependency():
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path.cwd() / "src")
    env["PYTHONIOENCODING"] = "utf-8"

    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "app.cli",
            "--query",
            "瑙ｉ噴涓€涓嬪崌鍔?,
            "--run-id",
            "run_cli_smoke",
            "--no-trace",
        ],
        cwd=Path.cwd(),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=15,
    )

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["run_id"] == "run_cli_smoke"
    assert payload["status"] == "ok"
    assert payload["trace"] == {}
```

## docs/superpowers/plans/_sdd/task-4-report.md

```
# Task 4 Report: Integrate Prompt Runtime into Agent and Generation

## Scope

- Wired `PromptRuntime` into `AgentRuntime` so prompt assembly happens after evidence retrieval and before generation.
- Removed generator-owned model prompt construction and made model generation consume `PromptMessageBundle.messages`.
- Added prompt audit metadata to `RunTrace` and `answer.generation_trace`.
- Kept offline/mock model tests runnable without API keys.

## Files Changed

- `src/core/contracts.py`
- `src/agent/runtime.py`
- `src/generation/generator.py`
- `tests/integration/app_loop/test_cli_pipeline.py`
- `tests/unit/generation/test_model_generation.py`
- `tests/unit/core/test_contracts.py`

## Implementation Notes

### 1. RunTrace prompt audit fields

Added:

- `prompt_version`
- `prompt_snapshot_id`
- `prompt_route_reason`
- `prompt_missing_variables`
- `prompt_injection_summary`

No prompt full text is stored in `RunTrace`.

### 2. AgentRuntime prompt bundle assembly

`AgentRuntime.__init__` now accepts:

```python
prompt_runtime: PromptRuntime | None = None
```

At runtime, after evidence is available:

- `prepare_route(...)` captures route reason.
- `assemble_bundle(...)` builds the canonical `PromptMessageBundle`.
- Trace prompt audit fields are filled from selection/bundle metadata.
- `generate_answer(...)` receives both `prompt_selection` and `prompt_bundle`.

### 3. Generator model-call behavior

`generate_answer(...)` now accepts:

```python
prompt_bundle: PromptMessageBundle | None = None
```

Changes:

- Deleted generator-owned `_build_model_messages`.
- Model calls now use `prompt_bundle.messages`.
- If `use_model=True` and a model call would occur without a prompt bundle, generation raises:

```text
prompt_bundle is required when use_model is true
```

- `answer.generation_trace` now includes:
  - `template_id`
  - `template_version`
  - `snapshot_id`
  - `route_reason`
  - `prompt_missing_variables`
  - `prompt_injection_summary`

No prompt full text is stored in `generation_trace`.

### 4. Test coverage added

- Model generation verifies prompt bundle driven tracing and bundle-required model calls.
- CLI pipeline integration verifies prompt trace fields are present and mirrored into `answer.generation_trace`.
- Contract test verifies new `RunTrace` prompt audit fields default correctly.

## Verification

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py tests/unit/core/test_contracts.py -q
```

Output:

```text
...........                                                              [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
................................                                         [100%]
```

## Result

Task 4 requirements are implemented within the requested file scope. Prompt assembly is now owned by `PromptRuntime`, model generation consumes canonical prompt bundles, and both runtime trace surfaces carry prompt audit metadata without leaking prompt body text.

## Review Fixes

- `PromptRuntime.assemble_bundle(...)` now accepts an optional precomputed `selection`, so `AgentRuntime` reuses one canonical `PromptSelection` for trace metadata and bundle assembly.
- Added a rule-path regression test that proves generation still works with `use_model=False` and no `prompt_selection`/`prompt_bundle`.
- Added a recording model-client test that verifies the exact `PromptMessageBundle.messages` are passed to the model client.

## Verification (Review Fix)

### Command 3

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_runtime.py tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
..............                                                           [100%]
```

### Command 4

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
...................................                                      [100%]
```
```
