"""Node registry — maps graph node names to their handlers.

The runtime uses this registry to dynamically assemble the LangGraph
without hard-coding node references in ``_compile_graph``.
"""

from __future__ import annotations

from typing import Any

from agent.nodes.base import NodeHandler


class NodeRegistry:
    """Singleton-like registry of named graph node handlers.

    Usage::

        registry = NodeRegistry()
        registry.register("context_resolution", my_node)
        node = registry.get("context_resolution")
    """

    def __init__(self) -> None:
        self._entries: dict[str, NodeHandler] = {}

    def register(self, name: str, handler: NodeHandler) -> None:
        """Register *handler* under *name*.

        Raises ``KeyError`` if *name* is already registered.
        """
        if name in self._entries:
            raise KeyError(f"node {name!r} is already registered")
        self._entries[name] = handler

    def get(self, name: str) -> NodeHandler:
        """Return the handler registered under *name*, or raise ``KeyError``."""
        return self._entries[name]

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(self._entries.keys())

    def items(self) -> list[tuple[str, NodeHandler]]:
        return list(self._entries.items())
