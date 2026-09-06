from __future__ import annotations

from dataclasses import replace

from agent.rewrite_engine import ClaimRewriteEngine
from core.action_contracts import TerminalActionResult
from core.actions import ActionDecision
from core.answer_contracts import AnswerEnvelope, AnswerType, GenerationTrace, ResponseMode
from core.runtime_settings import RuntimeSettings
from core.settings import load_settings
from safety.response_builder import SafetyResponseBuilder, SafetyResponseRequest
from self_check.contracts import CheckReport
from self_check.safety_checker import OperationalSafetyClassifier, PrePublishSafetyChecker


class AgentActionExecutor:
    """Apply only canonical typed self-check actions to canonical envelopes."""

    def __init__(
        self,
        safety_response_builder: SafetyResponseBuilder | None = None,
        rewrite_engine: ClaimRewriteEngine | None = None,
    ) -> None:
        runtime = RuntimeSettings.from_settings(load_settings("configs"))
        self._safety_response_builder = safety_response_builder or SafetyResponseBuilder(
            runtime.safety,
            self_check_settings=runtime.self_check,
        )
        self._rewrite_engine = rewrite_engine or ClaimRewriteEngine()
        self._prepublish = PrePublishSafetyChecker(
            OperationalSafetyClassifier(runtime.safety)
        )

    def terminal_answer(
        self,
        decision: ActionDecision,
        draft_answer: AnswerEnvelope,
        check_report: CheckReport,
    ) -> TerminalActionResult:
        if decision is ActionDecision.PASS:
            candidate = draft_answer
        elif decision is ActionDecision.SAFE_RESPONSE:
            candidate = self._safe_response(draft_answer, check_report.reason_codes)
        else:
            candidate = self._non_factual_terminal(draft_answer, decision)

        candidate.validate_cross_invariants()
        if self._prepublish.check(candidate).is_unsafe:
            candidate = self._safe_response(draft_answer, ("PREPUBLISH_UNSAFE",))
            candidate.validate_cross_invariants()
            return TerminalActionResult(
                envelope=candidate,
                action_decision=ActionDecision.SAFE_RESPONSE,
                reason_codes=("PREPUBLISH_UNSAFE",),
            )
        return TerminalActionResult(
            envelope=candidate,
            action_decision=decision,
            reason_codes=check_report.reason_codes,
        )

    def rewrite_only(
        self,
        draft_answer: AnswerEnvelope,
        check_report: CheckReport,
        _scene_state=None,
    ) -> AnswerEnvelope:
        if not isinstance(check_report, CheckReport):
            return self._non_factual_terminal(draft_answer, ActionDecision.REWRITE_ONLY)
        return self._rewrite_engine.rewrite(draft_answer, check_report).envelope

    def canonical_non_factual_envelope(
        self,
        *,
        run_id: str,
        decision: ActionDecision,
        reason_codes: tuple[str, ...] = (),
    ) -> AnswerEnvelope:
        """Build the canonical envelope for a supervisor terminal route.

        This keeps graph orchestration out of the answer-contract domain while
        preserving the existing safety builder as the sole refusal producer.
        """
        if decision is ActionDecision.SAFE_RESPONSE:
            return self._safety_response_builder.build(
                SafetyResponseRequest(
                    run_id=run_id,
                    source_answer_id=None,
                    reason_codes=reason_codes,
                    generation_trace=GenerationTrace(agent_action=decision.value),
                )
            )
        if decision is ActionDecision.PASS:
            text = "你好，我可以基于已审核资料解释航空科普问题。"
        elif decision is ActionDecision.ASK_CLARIFICATION:
            text = "请确认你要询问的具体对象或已审核资料？"
        elif decision is ActionDecision.HUMAN_REVIEW:
            text = "当前回答需要人工核验后才能发布。"
        elif decision is ActionDecision.STOP:
            text = "当前链路未能生成可安全发布的回答。"
        else:
            raise ValueError(f"unsupported non-factual action: {decision.value}")

        return AnswerEnvelope(
            answer_id=f"{run_id}_{decision.value.lower()}",
            answer_type=AnswerType.CLARIFICATION,
            response_mode=ResponseMode.CLARIFICATION_ONLY,
            short_answer=text,
            main_answer=text,
            evidence_refs=(),
            source_bindings=(),
            visual_refs=(),
            uncertainty_notes=(f"supervisor_terminal_{decision.value.lower()}",),
            safety_notes=(),
            follow_up_questions=("请补充更明确的问题或已审核资料。",),
            claim_candidates=(),
            display_blocks=(),
            generation_trace=GenerationTrace(agent_action=decision.value),
        )

    def _safe_response(
        self, draft_answer: AnswerEnvelope, reason_codes: tuple[str, ...]
    ) -> AnswerEnvelope:
        return self._safety_response_builder.build(
            SafetyResponseRequest(
                run_id=draft_answer.answer_id,
                source_answer_id=draft_answer.answer_id,
                reason_codes=reason_codes,
                generation_trace=draft_answer.generation_trace,
            )
        )

    @staticmethod
    def _non_factual_terminal(
        draft_answer: AnswerEnvelope, action: ActionDecision
    ) -> AnswerEnvelope:
        text = {
            ActionDecision.ASK_CLARIFICATION: "请确认你要询问的具体对象或已审核资料？",
            ActionDecision.HUMAN_REVIEW: "当前回答需要人工核验后才能发布。",
            ActionDecision.STOP: "当前链路未能生成可安全发布的回答。",
        }.get(action, "当前回答需要人工核验后才能发布。")
        return AnswerEnvelope(
            answer_id=f"{draft_answer.answer_id}_{action.value.lower()}",
            answer_type=AnswerType.CLARIFICATION,
            response_mode=ResponseMode.CLARIFICATION_ONLY,
            short_answer=text,
            main_answer=text,
            evidence_refs=(),
            source_bindings=(),
            visual_refs=(),
            uncertainty_notes=(f"self_check_terminal_{action.value.lower()}",),
            safety_notes=(),
            follow_up_questions=("请补充更明确的问题或已审核资料。",),
            claim_candidates=(),
            display_blocks=(),
            generation_trace=replace(
                draft_answer.generation_trace, agent_action=action.value
            ),
        )
