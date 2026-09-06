"""Supervisor route node."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class SupervisorRouteNode(NodeHandler):
    name = "supervisor_route"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._supervisor_route(state)
