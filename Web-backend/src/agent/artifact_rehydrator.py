"""Artifact rehydrator — rebuilds domain objects from checkpoint state.

All ``_rehydrate_*``, ``_required_*``, ``_fail_closed_*`` and related
recovery helpers that were previously on ``LangGraphAgentRuntime`` live
here so the runtime can focus on graph compilation and coordination.
"""

from __future__ import annotations

from hashlib import sha256

from agent.graph_contracts import GraphState


class ArtifactRehydrator:
    """Reconstruct typed domain objects from the minimal graph checkpoint state.

    Uses explicit version stamps to avoid unnecessary rehydration when the
    in-memory artifact cache is still consistent with the checkpoint (T19).
    """

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    # ── Version-stamp helpers (T19) ───────────────────────────────────

    @staticmethod
    def _fingerprint(*parts: str) -> str:
        """Deterministic hash for a set of input strings."""
        return sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]

    @staticmethod
    def _version_stamp(kind: str, state: GraphState) -> str:
        """Compute a version stamp that captures the relevant checkpoint keys.

        When the stamp matches what was stored alongside the in-memory
        artifact, the cached object can be reused without rehydration.
        """
        keys = {
            "evidence": ("evidence_fingerprint", "retrieval_plan_payload"),
            "resolution": ("normalized_query", "selected_object_id", "route_reason_codes"),
            "draft": ("draft_fingerprint", "rewrite_round"),
            "report": ("check_result", "action_decision"),
        }
        relevant = keys.get(kind, ())
        pieces = [str(state.get(k, "")) for k in relevant]
        pieces.insert(0, kind)
        return ArtifactRehydrator._fingerprint(*pieces)

    # ── rehydrate methods ──────────────────────────────────────────────

    def rehydrate_evidence(self, state: GraphState) -> object:
        return self._runtime._rehydrate_evidence(state)

    def rehydrate_resolution(self, state: GraphState) -> object:
        return self._runtime._rehydrate_resolution(state)

    def rehydrate_retrieval_terminal_artifacts(
        self, state: GraphState, plan: object
    ) -> object:
        return self._runtime._rehydrate_retrieval_terminal_artifacts(state, plan)

    # ── required-object accessors ──────────────────────────────────────

    def required_plan(self, state: GraphState) -> object:
        return self._runtime._required_plan(state)

    def required_evidence(self, state: GraphState, plan: object) -> object:
        return self._runtime._required_evidence(state, plan)

    def required_answer(self, state: GraphState) -> object:
        return self._runtime._required_answer(state)

    def required_report(self, state: GraphState) -> object:
        return self._runtime._required_report(state)

    # ── fail-closed helpers ────────────────────────────────────────────

    def fail_closed_draft(
        self, state: GraphState, error_code: str
    ) -> dict[str, object]:
        return self._runtime._fail_closed_draft(state, error_code)

    def fail_closed_check(
        self, state: GraphState, error_code: str
    ) -> dict[str, object]:
        return self._runtime._fail_closed_check(state, error_code)

    # ── terminal / error helpers ───────────────────────────────────────

    def terminal_decision(self, state: GraphState) -> object:
        return self._runtime._terminal_decision(state)

    def terminal_report(
        self, state: GraphState, decision: object, reason_codes: tuple[str, ...]
    ) -> object:
        return self._runtime._terminal_report(state, decision, reason_codes)

    def error_response(
        self, trace: object, code: str, message: str
    ) -> object:
        return self._runtime._error_response(trace, code, message)
