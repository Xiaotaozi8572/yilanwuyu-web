"""Context resolution node."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class ContextResolutionNode(NodeHandler):
    name = "context_resolution"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._context_resolution(state)
