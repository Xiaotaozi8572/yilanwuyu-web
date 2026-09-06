"""Self-check node — validates the draft answer against safety and evidence."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class SelfCheckNode(NodeHandler):
    name = "self_check"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._self_check(state)
