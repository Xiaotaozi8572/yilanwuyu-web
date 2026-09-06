### Task 4: Integrate Prompt Runtime into Agent and Generation

**Files:**
- Modify: `src/core/contracts.py`
- Modify: `src/agent/runtime.py`
- Modify: `src/generation/generator.py`
- Modify: `src/services/model_client.py` only if type imports require adjustment.
- Test: `tests/integration/app_loop/test_cli_pipeline.py`
- Test: `tests/unit/generation/test_model_generation.py`

**Interfaces:**
- Consumes: `PromptRuntime`, `PromptMessageBundle`.
- Produces: runtime traces with prompt template id, version, snapshot id, route reason, missing variables, and injection summary.

- [ ] **Step 1: Write integration test for prompt trace**

```python
# tests/integration/app_loop/test_cli_pipeline.py
from app.api.schemas import TextQueryRequest
from services.app_pipeline import AppPipeline


def test_text_query_records_prompt_trace_fields():
    response = AppPipeline().run_text_query(TextQueryRequest(query="Explain lift", run_id="prompt_trace_test"))

    assert response.status == "ok"
    assert response.trace["prompt_template_id"]
    assert response.trace["prompt_version"]
    assert isinstance(response.trace["prompt_injection_summary"], list)
    assert response.answer["generation_trace"]["template_id"] == response.trace["prompt_template_id"]
```

- [ ] **Step 2: Extend RunTrace**

Add fields to `RunTrace`:

```python
prompt_version: str | None = None
prompt_snapshot_id: str | None = None
prompt_route_reason: str | None = None
prompt_missing_variables: list[str] = field(default_factory=list)
prompt_injection_summary: list[dict[str, Any]] = field(default_factory=list)
```

- [ ] **Step 3: Inject PromptRuntime in AgentRuntime**

Add constructor dependency:

```python
from prompts.runtime import PromptRuntime

class AgentRuntime:
    def __init__(..., prompt_runtime: PromptRuntime | None = None, ...):
        self.prompt_runtime = prompt_runtime or PromptRuntime.from_config()
```

After evidence retrieval and before generation:

```python
prompt_bundle = self.prompt_runtime.assemble_bundle(
    query_object=query_object,
    scene_state=scene_state,
    memory_context=memory_context,
    evidence_package=evidence_package,
    output_contract={"type": "answer_envelope"},
)
trace.prompt_template_id = prompt_bundle.template_id
trace.prompt_version = prompt_bundle.version
trace.prompt_snapshot_id = prompt_bundle.snapshot_id
trace.prompt_missing_variables = list(prompt_bundle.missing_variables)
trace.prompt_injection_summary = list(prompt_bundle.injection_summary)
```

Pass `prompt_bundle=prompt_bundle` into `generate_answer()`.

- [ ] **Step 4: Remove generator-owned model message construction**

Change `GroundedAnswerGenerator.generate_answer()` signature to include `prompt_bundle: PromptMessageBundle`.

Replace:

```python
self._build_model_messages(plan, evidence_sketch)
```

with:

```python
prompt_bundle.messages
```

Delete `_build_model_messages()`.

Set trace values:

```python
"template_id": prompt_bundle.template_id,
"template_version": prompt_bundle.version,
"prompt_snapshot_id": prompt_bundle.snapshot_id,
```

- [ ] **Step 5: Update generation tests**

Add this assertion to model generation tests:

```python
assert answer.generation_trace["template_id"] == "aviation_fact_qa"
assert answer.generation_trace["template_version"] == "v2"
```

Use `PromptRuntime.from_config().assemble_bundle(...)` to create the prompt bundle in the test.

- [ ] **Step 6: Run integration and generation tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/generation tests/integration/app_loop -q
```

Expected: tests pass and no generation test imports `_build_model_messages`.

- [ ] **Step 7: Commit**

```powershell
git add src/core/contracts.py src/agent/runtime.py src/generation/generator.py tests/unit/generation tests/integration/app_loop
git commit -m "refactor: use prompt runtime in generation chain"
```

---

