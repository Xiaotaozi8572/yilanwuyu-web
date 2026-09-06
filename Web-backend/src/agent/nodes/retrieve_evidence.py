"""Retrieve evidence node."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class RetrieveEvidenceNode(NodeHandler):
    name = "retrieve_evidence"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._retrieve_evidence(state)
