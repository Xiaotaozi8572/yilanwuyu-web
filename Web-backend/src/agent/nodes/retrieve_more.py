"""Retrieve-more node — expands evidence when self-check demands more."""

from __future__ import annotations

from agent.nodes.base import NodeHandler
from agent.graph_contracts import GraphState


class RetrieveMoreNode(NodeHandler):
    name = "retrieve_more"

    def __init__(self, runtime: object) -> None:
        self._runtime = runtime

    def __call__(self, state: GraphState) -> GraphState:
        return self._runtime._retrieve_more(state)
