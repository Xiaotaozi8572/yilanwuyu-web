from __future__ import annotations

from dataclasses import replace

from core.action_contracts import (
    AnswerLoopState,
    RecoveryContext,
    RetrievalActionResult,
)
from core.actions import ActionDecision
from core.errors import EvidenceIdentityCollisionError
from core.runtime_settings import RetrievalSettings
from knowledge.evidence_package import (
    evidence_package_fingerprint,
    merge_evidence_packages,
)
from knowledge.retrieval_controller import (
    RetrievalController,
    refine_retrieval_plan,
    retrieval_plan_fingerprint,
)


class RetrievalActionService:
    """Run exactly one frozen-directive retrieval recovery attempt."""

    def __init__(
        self, retrieval_controller: RetrievalController, settings: RetrievalSettings
    ) -> None:
        if not isinstance(settings, RetrievalSettings):
            raise TypeError("settings must be RetrievalSettings")
        self._retrieval_controller = retrieval_controller
        self._settings = settings

    def retrieve_more(
        self, context: RecoveryContext, state: AnswerLoopState
    ) -> RetrievalActionResult:
        report = context.report
        if report.action_decision is not ActionDecision.RETRIEVE_MORE:
            return self._failure(context, state, "invalid_retrieval_action")
        directive = report.retrieval_directive
        if directive is None:
            return self._failure(context, state, "missing_retrieval_directive")
        if directive.retrieve_round != state.retrieve_round + 1:
            return self._failure(context, state, "retrieval_round_mismatch")

        try:
            plan = refine_retrieval_plan(
                context.previous_plan, directive, self._settings
            )
        except (TypeError, ValueError):
            return self._failure(context, state, "retrieval_refinement_failed")

        try:
            recovered = self._retrieval_controller.retrieve_evidence(plan)
            merged = merge_evidence_packages(context.previous_evidence, recovered)
            evidence_package = self._retrieval_controller.regate_evidence_package(merged)
        except EvidenceIdentityCollisionError:
            return self._failure(context, state, "evidence_identity_collision", plan)
        except Exception:
            return self._failure(context, state, "retrieval_recovery_failed", plan)

        plan_fingerprint = retrieval_plan_fingerprint(plan)
        evidence_fingerprint = evidence_package_fingerprint(evidence_package)
        progress_made = (
            plan_fingerprint != state.last_plan_fingerprint
            or evidence_fingerprint != state.last_evidence_fingerprint
        )
        next_state = replace(
            state,
            retrieve_round=directive.retrieve_round,
            last_plan_fingerprint=plan_fingerprint,
            last_evidence_fingerprint=evidence_fingerprint,
        )
        return RetrievalActionResult(
            plan=plan,
            evidence_package=evidence_package,
            state=next_state,
            progress_made=progress_made,
        )

    @staticmethod
    def _failure(
        context: RecoveryContext,
        state: AnswerLoopState,
        error_code: str,
        plan=None,
    ) -> RetrievalActionResult:
        return RetrievalActionResult(
            plan=plan or context.previous_plan,
            evidence_package=context.previous_evidence,
            state=state,
            progress_made=False,
            error_code=error_code,
        )
