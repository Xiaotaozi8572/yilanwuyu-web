"""Rewrite node — revises the draft based on self-check feedback."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class RewriteNode(NodeHandler):
    name = "rewrite"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._rewrite(state)
