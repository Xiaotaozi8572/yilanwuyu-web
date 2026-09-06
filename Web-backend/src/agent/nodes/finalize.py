"""Finalize node — assembles the terminal response from graph state."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class FinalizeNode(NodeHandler):
    name = "finalize"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._finalize(state)
