### Task 1: Replace Prompt Domain Models

**Files:**
- Modify: `src/prompts/asset_models.py`
- Modify: `src/core/contracts.py`
- Test: `tests/unit/prompts/test_prompt_models.py`

**Interfaces:**
- Produces: `PromptStatus`, `PromptVariableSpec`, `PromptContentVersion`, `TeachingPromptAsset`, `PromptSelection`, `PromptMessageBundle`, `PromptEvaluationSnapshot`, `PromptGovernanceResult`.
- Consumes: `BaseContract`, `ModelMessage`.

- [ ] **Step 1: Write failing model tests**

```python
# tests/unit/prompts/test_prompt_models.py
import pytest

from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptMessageBundle,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)
from services.model_client import ModelMessage


def test_prompt_variable_spec_requires_name_source_and_missing_behavior():
    spec = PromptVariableSpec(
        name="rag_evidence",
        source="evidence_package",
        value_type="evidence_summary",
        required=True,
        missing_behavior="clarification",
        description="Reviewed evidence used as the only fact source.",
    )

    assert spec.name == "rag_evidence"
    assert spec.required is True


def test_prompt_message_bundle_uses_model_messages_only():
    bundle = PromptMessageBundle(
        messages=[ModelMessage(role="system", content="facts from evidence only")],
        injection_order=["system_boundary"],
        template_id="aviation_fact_qa",
        version="v1",
        snapshot_id="aviation_fact_qa_v1",
        injection_summary=[{"section": "system_boundary", "chars": 24}],
    )

    assert bundle.messages[0].role == "system"
    assert bundle.template_id == "aviation_fact_qa"


def test_active_asset_points_to_existing_active_version_shape():
    version = PromptContentVersion(
        template_id="aviation_fact_qa",
        version="v1",
        content="Use {{rag_evidence}} only.",
        review_status=PromptStatus.ACTIVE,
        snapshot_id="aviation_fact_qa_v1",
        variables=[
            PromptVariableSpec(
                name="rag_evidence",
                source="evidence_package",
                value_type="evidence_summary",
                required=True,
                missing_behavior="clarification",
                description="Reviewed evidence.",
            )
        ],
    )
    asset = TeachingPromptAsset(
        template_id="aviation_fact_qa",
        title="Aviation factual answer",
        task_type="concept_explanation",
        status=PromptStatus.ACTIVE,
        active_version="v1",
        activation_scope=["concept_explanation"],
        constraints=["facts_from_evidence_only"],
        risk_boundaries=["no_parameter_without_source"],
    )

    assert asset.active_version == version.version
    assert asset.status == PromptStatus.ACTIVE


def test_evaluation_snapshot_has_final_decision():
    snapshot = PromptEvaluationSnapshot(
        snapshot_id="aviation_fact_qa_v1",
        template_id="aviation_fact_qa",
        version="v1",
        test_cases=["basic_concept"],
        dimension_scores={"faithfulness": 1.0},
        violations=[],
        improvements=["clearer evidence wording"],
        final_decision="approve",
    )

    assert snapshot.final_decision == "approve"
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

Expected: fails because the new dataclasses do not exist.

- [ ] **Step 3: Replace `src/prompts/asset_models.py`**

Use this implementation shape:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from core.contracts import BaseContract
from services.model_client import ModelMessage


class PromptStatus(StrEnum):
    DRAFT = "draft"
    CANDIDATE = "candidate"
    ACTIVE = "active"
    EXPERIMENTAL = "experimental"
    DEPRECATED = "deprecated"


@dataclass
class PromptVariableSpec(BaseContract):
    required_fields = ("name", "source", "value_type", "missing_behavior")

    name: str = ""
    source: str = ""
    value_type: str = "text"
    required: bool = False
    missing_behavior: str = "omit"
    description: str = ""


@dataclass
class PromptContentVersion(BaseContract):
    required_fields = ("template_id", "version", "content", "review_status")

    template_id: str = ""
    version: str = ""
    content: str = ""
    parent_version: str | None = None
    change_reason: str = ""
    created_at: str = ""
    review_status: PromptStatus = PromptStatus.DRAFT
    snapshot_id: str | None = None
    variables: list[PromptVariableSpec] = field(default_factory=list)


@dataclass
class TeachingPromptAsset(BaseContract):
    required_fields = ("template_id", "title", "task_type", "status", "active_version")

    template_id: str = ""
    title: str = ""
    task_type: str = ""
    concept_scope: list[str] = field(default_factory=list)
    scene_scope: list[str] = field(default_factory=list)
    status: PromptStatus = PromptStatus.DRAFT
    active_version: str = ""
    activation_scope: list[str] = field(default_factory=list)
    constraints: list[str] = field(default_factory=list)
    risk_boundaries: list[str] = field(default_factory=list)


@dataclass
class PromptSelection(BaseContract):
    template_id: str = ""
    version: str = ""
    task_type: str = ""
    status: PromptStatus = PromptStatus.ACTIVE
    route_reason: str = ""
    missing_variables: list[str] = field(default_factory=list)
    selected_asset: TeachingPromptAsset | None = None
    selected_version: PromptContentVersion | None = None
    is_clarification: bool = False


@dataclass
class PromptMessageBundle(BaseContract):
    messages: list[ModelMessage] = field(default_factory=list)
    injection_order: list[str] = field(default_factory=list)
    template_id: str = ""
    version: str = ""
    snapshot_id: str | None = None
    injection_summary: list[dict[str, Any]] = field(default_factory=list)
    missing_variables: list[str] = field(default_factory=list)
    is_final_fact_prompt: bool = True


@dataclass
class PromptOptimizationRecord(BaseContract):
    input_version: str = ""
    output_draft_version: str = ""
    model: str = ""
    instruction: str = ""
    trigger_event: str = ""
    created_at: str = ""


@dataclass
class PromptExample(BaseContract):
    variables: dict[str, Any] = field(default_factory=dict)
    rag_requirement: str = ""
    expected_focus: list[str] = field(default_factory=list)
    failure_modes: list[str] = field(default_factory=list)
    real_output: str = ""
    score: dict[str, float] = field(default_factory=dict)


@dataclass
class PromptEvaluationSnapshot(BaseContract):
    required_fields = ("snapshot_id", "template_id", "version", "final_decision")

    snapshot_id: str = ""
    template_id: str = ""
    version: str = ""
    test_cases: list[str] = field(default_factory=list)
    dimension_scores: dict[str, float] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)
    improvements: list[str] = field(default_factory=list)
    final_decision: str = ""


@dataclass
class CompareStopSignals(BaseContract):
    target_vs_baseline: str = ""
    reference_gap: str = ""
    overfit_risk: str = "unknown"
    stop_recommendation: str = ""


@dataclass
class PromptGovernanceResult(BaseContract):
    template_id: str = ""
    version: str = ""
    action: str = ""
    ok: bool = False
    reason: str = ""
```

- [ ] **Step 4: Remove the duplicate core prompt contract**

In `src/core/contracts.py`, delete the `PromptAsset` dataclass and remove `"prompt_asset": PromptAsset` from `CONTRACT_TYPES`.

- [ ] **Step 5: Run model tests**

Run:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

Expected: `4 passed`.

- [ ] **Step 6: Commit**

```powershell
git add src/prompts/asset_models.py src/core/contracts.py tests/unit/prompts/test_prompt_models.py
git commit -m "refactor: define prompt domain models"
```

---

