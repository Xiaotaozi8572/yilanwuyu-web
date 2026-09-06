### Task 3: Refactor Router, Assembler, and Prompt Runtime

**Files:**
- Modify: `src/prompts/router.py`
- Modify: `src/prompts/assembler.py`
- Create: `src/prompts/runtime.py`
- Modify: `src/prompts/__init__.py`
- Test: `tests/unit/prompts/test_prompt_router.py`
- Test: `tests/unit/prompts/test_prompt_assembler.py`
- Test: `tests/unit/prompts/test_prompt_runtime.py`

**Interfaces:**
- Consumes: `PromptAssetRepository`, `PromptSelection`, `PromptContentVersion`, `PromptVariableSpec`.
- Produces: `PromptRuntime.prepare_route()`, `PromptRuntime.assemble_bundle()`, `PromptMessageBundle`.

- [ ] **Step 1: Replace router tests**

```python
# tests/unit/prompts/test_prompt_router.py
import pytest

from core.contracts import EvidenceItem, EvidencePackage, SceneState
from input.query_object import QueryObject
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptStatusError


def _evidence():
    return EvidencePackage(evidence_items=[EvidenceItem(evidence_id="ev1", source_id="src1", content="reviewed fact")])


def test_router_selects_active_asset_for_intent():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")

    selection = router.select_prompt(query, evidence_package=_evidence(), output_contract={"type": "answer_envelope"})

    assert selection.template_id == "aviation_fact_qa"
    assert selection.version == "v2"
    assert selection.route_reason == "intent_scope_match"


def test_missing_rag_evidence_routes_to_clarification_prompt():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")

    selection = router.select_prompt(query, scene_state=SceneState(), output_contract={"type": "answer_envelope"})

    assert selection.template_id == "aviation_basic_safe"
    assert selection.is_clarification is True
    assert "rag_evidence" in selection.missing_variables


def test_candidate_assets_are_not_runtime_routable():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="q", normalized_query="q", intent_type="experimental_teaching_strategy")

    with pytest.raises(PromptStatusError):
        router.select_prompt(query, evidence_package=_evidence(), output_contract={"type": "answer_envelope"})
```

- [ ] **Step 2: Implement narrowed router**

```python
# src/prompts/router.py
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from input.query_object import QueryObject
from prompts.asset_models import PromptSelection
from prompts.repository import PromptAssetRepository


class PromptStatusError(Exception):
    pass


@dataclass
class PromptRouterConfig:
    default_template_id: str = "aviation_fact_qa"
    clarification_template_id: str = "aviation_basic_safe"
    required_final_fact_variables: list[str] = field(default_factory=lambda: ["rag_evidence", "output_contract"])
    injection_order: list[str] = field(default_factory=lambda: ["system_boundary", "task_type", "scene_state", "rag_evidence", "memory_context", "weak_points", "prompt_asset", "output_contract"])

    @classmethod
    def from_file(cls, path: str | Path = "configs/prompts.yaml") -> "PromptRouterConfig":
        prompts = parse_simple_yaml(Path(path).read_text(encoding="utf-8")).get("prompts", {})
        return cls(
            default_template_id=prompts.get("default_template_id", "aviation_fact_qa"),
            clarification_template_id=prompts.get("clarification_template_id", "aviation_basic_safe"),
            required_final_fact_variables=prompts.get("required_final_fact_variables", ["rag_evidence", "output_contract"]),
            injection_order=prompts.get("injection_order", cls().injection_order),
        )


class PromptRouter:
    def __init__(self, repository: PromptAssetRepository | None = None, config: PromptRouterConfig | None = None) -> None:
        self.repository = repository or PromptAssetRepository.from_config()
        self.config = config or PromptRouterConfig.from_file()

    def select_prompt(
        self,
        query_object: QueryObject,
        scene_state: Any | None = None,
        memory_context: Any | None = None,
        evidence_package: Any | None = None,
        output_contract: Any | None = None,
    ) -> PromptSelection:
        available = self._available_variables(scene_state, memory_context, evidence_package, output_contract)
        missing = [name for name in self.config.required_final_fact_variables if name not in available]
        if missing:
            return self._clarification(missing)

        asset = self.repository.find_for_intent(query_object.intent_type)
        if asset is None:
            asset = self.repository.get_asset(self.config.default_template_id)
            if asset.status.value not in self.repository.allowed_runtime_statuses:
                raise PromptStatusError(f"default prompt is not runtime eligible: {asset.template_id}")
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason="intent_scope_match",
            selected_asset=asset,
            selected_version=version,
        )

    def _clarification(self, missing: list[str]) -> PromptSelection:
        asset = self.repository.get_asset(self.config.clarification_template_id)
        if asset.status.value not in self.repository.allowed_runtime_statuses:
            raise PromptStatusError(f"clarification prompt is not runtime eligible: {asset.template_id}")
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason="missing_required_variables",
            missing_variables=missing,
            selected_asset=asset,
            selected_version=version,
            is_clarification=True,
        )

    def _available_variables(self, scene_state: Any | None, memory_context: Any | None, evidence_package: Any | None, output_contract: Any | None) -> set[str]:
        available: set[str] = set()
        if scene_state is not None:
            available.add("scene_state")
        if memory_context is not None:
            available.add("memory_context")
        if evidence_package is not None and getattr(evidence_package, "evidence_items", None):
            available.add("rag_evidence")
        if output_contract is not None:
            available.add("output_contract")
        return available
```

- [ ] **Step 3: Replace assembler tests**

```python
# tests/unit/prompts/test_prompt_assembler.py
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts.runtime import PromptRuntime


def test_prompt_runtime_injects_rag_before_prompt_asset_and_uses_model_messages():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")
    evidence = EvidencePackage(evidence_items=[EvidenceItem(evidence_id="ev_lift", source_id="src_lift", content="Lift depends on airflow evidence.")])

    bundle = runtime.assemble_bundle(
        query_object=query,
        scene_state=SceneState(component_id="wing"),
        memory_context=MemoryContext(session_preference={"explanation_preference": "plain"}),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
        weak_points=["confuses lift and thrust"],
    )

    assert bundle.injection_order.index("rag_evidence") < bundle.injection_order.index("prompt_asset")
    assert all(message.role in {"system", "user"} for message in bundle.messages)
    assert "Lift depends on airflow evidence." in "\n".join(message.content for message in bundle.messages)
    assert "aviation science tutor" in "\n".join(message.content for message in bundle.messages)
```

- [ ] **Step 4: Implement assembler and runtime**

Implement `PromptAssembler` with these key methods:

```python
class PromptAssembler:
    def assemble_messages(self, selection: PromptSelection, variables: dict[str, str], injection_order: list[str]) -> PromptMessageBundle:
        rendered_prompt = self._render(selection.selected_version.content, variables)
        sections = {
            "system_boundary": "Facts must come only from reviewed evidence. Memory and prompt assets are not fact sources.",
            "task_type": variables.get("task_type", ""),
            "scene_state": variables.get("scene_state", ""),
            "rag_evidence": variables.get("rag_evidence", ""),
            "memory_context": variables.get("memory_context", ""),
            "weak_points": variables.get("weak_points", ""),
            "prompt_asset": rendered_prompt,
            "output_contract": variables.get("output_contract", ""),
        }
        system_content = "\n\n".join([sections["system_boundary"], sections["output_contract"]])
        user_content = "\n\n".join(f"[{name}]\n{sections[name]}" for name in injection_order if name not in {"system_boundary", "output_contract"})
        messages = [ModelMessage(role="system", content=system_content), ModelMessage(role="user", content=user_content)]
        return PromptMessageBundle(
            messages=messages,
            injection_order=list(injection_order),
            template_id=selection.template_id,
            version=selection.version,
            snapshot_id=selection.selected_version.snapshot_id,
            injection_summary=self._summary(sections, injection_order),
            missing_variables=list(selection.missing_variables),
            is_final_fact_prompt=not selection.is_clarification,
        )
```

Implement `PromptRuntime` as the only public runtime API:

```python
class PromptRuntime:
    def __init__(self, repository: PromptAssetRepository, router: PromptRouter, assembler: PromptAssembler, config: PromptRouterConfig) -> None:
        self.repository = repository
        self.router = router
        self.assembler = assembler
        self.config = config

    @classmethod
    def from_config(cls, path: str = "configs/prompts.yaml") -> "PromptRuntime":
        repository = PromptAssetRepository.from_config(path)
        config = PromptRouterConfig.from_file(path)
        return cls(repository, PromptRouter(repository, config), PromptAssembler(), config)

    def prepare_route(self, query_object, scene_state=None):
        return self.router.select_prompt(query_object, scene_state=scene_state, output_contract={"type": "answer_envelope"})

    def assemble_bundle(self, query_object, scene_state, memory_context, evidence_package, output_contract, weak_points=None):
        selection = self.router.select_prompt(query_object, scene_state, memory_context, evidence_package, output_contract)
        variables = self._bind_variables(query_object, scene_state, memory_context, evidence_package, output_contract, weak_points or [], selection)
        return self.assembler.assemble_messages(selection, variables, self.config.injection_order)
```

- [ ] **Step 5: Update exports**

`src/prompts/__init__.py` must export `PromptRuntime`, `PromptAssetRepository`, `PromptRouter`, `PromptAssembler`, and the domain models.

- [ ] **Step 6: Run prompt runtime tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Expected: all prompt unit tests pass.

- [ ] **Step 7: Commit**

```powershell
git add src/prompts tests/unit/prompts
git commit -m "refactor: route and assemble prompt runtime bundles"
```

---

