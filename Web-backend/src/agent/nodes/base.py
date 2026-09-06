"""Base node protocol for LangGraph agent nodes."""

from __future__ import annotations

from agent.graph_contracts import GraphState


class NodeHandler:
    """A single LangGraph node that transforms ``GraphState`` → ``GraphState``.

    Subclasses set ``name`` as a class attribute and implement ``__call__``.
    """

    name: str = ""

    def __call__(self, state: GraphState) -> GraphState:
        raise NotImplementedError
