from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from agent.graph_contracts import SupervisorRoute
from core.contracts import MemoryContext, SceneState
from input.query_object import QueryObject
from input.query_understanding import QueryUnderstandingService


_APPROVED_NO_FACT_CONVERSATIONS = frozenset(
    {"你好", "您好", "谢谢", "再见", "你能做什么", "怎么使用", "继续"}
)
_REFERENCE_MARKERS = ("这个", "那个", "旁边", "刚才", "这里", "它", "this", "that")


def _contains_reference(query: str) -> bool:
    lowered = query.lower()
    return any(marker in lowered for marker in _REFERENCE_MARKERS)


@dataclass(frozen=True)
class ResolvedQuery:
    query_object: QueryObject
    selected_object_id: str | None
    candidate_object_ids: tuple[str, ...]
    reason_codes: tuple[str, ...]


@dataclass(frozen=True)
class SupervisorDecision:
    route: SupervisorRoute
    reason_codes: tuple[str, ...]


@dataclass(frozen=True)
class _ReferenceCandidate:
    object_id: str
    source: Literal["scene", "aircraft", "component", "concept"]


class ConversationReferenceResolver:
    """Resolve only object identifiers from summaries in the current session."""

    def __init__(self, query_understanding: QueryUnderstandingService | None = None) -> None:
        self._query_understanding = query_understanding or QueryUnderstandingService()

    def resolve(
        self,
        query: str,
        memory_context: MemoryContext | None = None,
        scene_state: SceneState | None = None,
        *,
        unsafe: bool = False,
    ) -> ResolvedQuery:
        if memory_context is not None and not isinstance(memory_context, MemoryContext):
            raise TypeError("memory_context must be MemoryContext or None")
        if unsafe:
            query_object = self._query_understanding.understand_query(query)
            return ResolvedQuery(
                query_object=query_object,
                selected_object_id=None,
                candidate_object_ids=(),
                reason_codes=("reference_resolution_unsafe_short_circuit",),
            )

        dialogue_context = self._same_session_dialogue_context(memory_context)
        query_object = self._query_understanding.understand_query(
            query, scene_state=scene_state,
            dialogue_context=dialogue_context,
        )
        if not _contains_reference(query_object.normalized_query):
            return ResolvedQuery(query_object, None, (), ())

        candidates = [*self._current_scene_candidates(scene_state)]
        for summary in reversed(dialogue_context):
            historical_query = self._query_understanding.understand_query(summary)
            candidates.extend(self._historical_candidates(historical_query))

        deduplicated = self._deduplicate(candidates)
        selected = deduplicated[0] if deduplicated else None
        if selected is None:
            return ResolvedQuery(query_object, None, (), ())

        self._apply_selected_object(query_object, selected)
        reason = (
            "reference_strategy_current_scene"
            if selected.source == "scene"
            else "reference_strategy_most_recent_explicit"
        )
        return ResolvedQuery(
            query_object=query_object,
            selected_object_id=selected.object_id,
            candidate_object_ids=tuple(candidate.object_id for candidate in deduplicated),
            reason_codes=(reason,),
        )

    def _same_session_dialogue_context(
        self,
        memory_context: MemoryContext | None,
    ) -> list[str]:
        if memory_context is None:
            return []
        if not isinstance(memory_context, MemoryContext):
            raise TypeError("memory_context must be MemoryContext or None")
        return list(memory_context.dialogue_context)

    def _current_scene_candidates(
        self,
        scene_state: SceneState | None,
    ) -> list[_ReferenceCandidate]:
        if scene_state is None:
            return []
        object_ids = [
            scene_state.selected_object_id,
            *scene_state.candidate_object_ids,
        ]
        return [
            _ReferenceCandidate(object_id=object_id, source="scene")
            for object_id in object_ids
            if object_id
        ]

    def _historical_candidates(self, query_object: QueryObject) -> list[_ReferenceCandidate]:
        values: tuple[tuple[str | None, Literal["aircraft", "component", "concept", "scene"]], ...] = (
            (query_object.target_aircraft, "aircraft"),
            (query_object.target_component, "component"),
            (query_object.target_concept, "concept"),
            (query_object.scene_object_id, "scene"),
        )
        return [
            _ReferenceCandidate(object_id=object_id, source=source)
            for object_id, source in values
            if object_id
        ]

    def _deduplicate(
        self,
        candidates: list[_ReferenceCandidate],
    ) -> list[_ReferenceCandidate]:
        seen: set[str] = set()
        deduplicated: list[_ReferenceCandidate] = []
        for candidate in candidates:
            if candidate.object_id in seen:
                continue
            seen.add(candidate.object_id)
            deduplicated.append(candidate)
        return deduplicated

    def _apply_selected_object(
        self,
        query_object: QueryObject,
        selected: _ReferenceCandidate,
    ) -> None:
        if selected.source == "scene":
            query_object.scene_object_id = selected.object_id
        elif selected.source == "component":
            query_object.target_component = selected.object_id
        elif selected.source == "aircraft":
            query_object.target_aircraft = selected.object_id
        else:
            query_object.target_concept = selected.object_id

class SupervisorRouter:
    """Apply the contractual initial supervision priority without retrieval."""

    def decide(
        self,
        query: str,
        unsafe: bool,
        resolution: ResolvedQuery,
    ) -> SupervisorDecision:
        if unsafe:
            return SupervisorDecision(
                route=SupervisorRoute.REFUSE,
                reason_codes=("supervisor_unsafe_input",),
            )

        normalized_query = " ".join(query.strip().split())
        if normalized_query in _APPROVED_NO_FACT_CONVERSATIONS:
            return SupervisorDecision(
                route=SupervisorRoute.DIRECT,
                reason_codes=("supervisor_approved_no_fact_conversation",),
            )

        if resolution.query_object.needs_clarification:
            return SupervisorDecision(
                route=SupervisorRoute.CLARIFY,
                reason_codes=("supervisor_query_needs_clarification",),
            )

        if self._is_unresolved_reference(normalized_query, resolution):
            return SupervisorDecision(
                route=SupervisorRoute.CLARIFY,
                reason_codes=("supervisor_unresolved_reference",),
            )

        return SupervisorDecision(
            route=SupervisorRoute.RETRIEVE,
            reason_codes=("supervisor_retrieval_required",),
        )

    def _is_unresolved_reference(
        self,
        query: str,
        resolution: ResolvedQuery,
    ) -> bool:
        if not _contains_reference(query):
            return False
        if resolution.selected_object_id is not None:
            return False
        query_object = resolution.query_object
        return not any(
            (
                query_object.target_aircraft,
                query_object.target_component,
                query_object.target_concept,
                query_object.scene_object_id,
            )
        )
