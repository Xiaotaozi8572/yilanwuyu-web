from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import TypedDict


class SupervisorRoute(StrEnum):
    DIRECT = "DIRECT"
    RETRIEVE = "RETRIEVE"
    CLARIFY = "CLARIFY"
    REFUSE = "REFUSE"


@dataclass(frozen=True)
class LangGraphSettings:
    checkpoint_path: str
    max_resume_attempts: int


class GraphState(TypedDict, total=False):
    run_id: str
    source: str
    user_id: str | None
    session_id: str | None
    turn_id: str | None
    normalized_query: str
    request_fingerprint: str
    scene_state: dict[str, object] | None
    route: str
    route_reason_codes: list[str]
    selected_object_id: str | None
    selected_object_kind: str | None
    retrieval_plan_payload: dict[str, object]
    evidence_fingerprint: str
    draft_fingerprint: str
    action_decision: str
    retrieve_round: int
    rewrite_round: int
    resume_attempt: int
    terminal: bool
    error_code: str | None


def initial_graph_state(
    *,
    run_id: str,
    query: str,
    source: str,
    user_id: str | None = None,
    session_id: str | None = None,
    turn_id: str | None = None,
    request_fingerprint: str = "",
    scene_state: dict[str, object] | None = None,
) -> GraphState:
    """Create the smallest JSON-safe state needed to begin a graph run."""
    state: GraphState = {
        "run_id": run_id,
        "source": source,
        "normalized_query": query,
        "request_fingerprint": request_fingerprint,
        "route_reason_codes": [],
        "retrieve_round": 0,
        "rewrite_round": 0,
        "resume_attempt": 0,
        "terminal": False,
    }
    if user_id is not None:
        state["user_id"] = user_id
    if session_id is not None:
        state["session_id"] = session_id
    if turn_id is not None:
        state["turn_id"] = turn_id
    if scene_state is not None:
        state["scene_state"] = scene_state
    return state
