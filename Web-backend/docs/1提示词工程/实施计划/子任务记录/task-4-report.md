# Task 4 Report: Integrate Prompt Runtime into Agent and Generation

## Scope

- Wired `PromptRuntime` into `AgentRuntime` so prompt assembly happens after evidence retrieval and before generation.
- Removed generator-owned model prompt construction and made model generation consume `PromptMessageBundle.messages`.
- Added prompt audit metadata to `RunTrace` and `answer.generation_trace`.
- Kept offline/mock model tests runnable without API keys.

## Files Changed

- `src/prompts/asset_models.py`
- `src/prompts/assembler.py`
- `src/core/contracts.py`
- `src/agent/runtime.py`
- `src/generation/generator.py`
- `tests/unit/prompts/test_prompt_models.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `tests/unit/prompts/test_prompt_runtime.py`
- `tests/integration/app_loop/test_cli_pipeline.py`
- `tests/unit/generation/test_model_generation.py`
- `tests/unit/core/test_contracts.py`
- `docs/superpowers/plans/_sdd/task-4-report.md`

## Implementation Notes

### 1. RunTrace prompt audit fields

Added:

- `prompt_version`
- `prompt_snapshot_id`
- `prompt_route_reason`
- `prompt_missing_variables`
- `prompt_injection_summary`

No prompt full text is stored in `RunTrace`.

### 2. AgentRuntime prompt bundle assembly

`AgentRuntime.__init__` now accepts:

```python
prompt_runtime: PromptRuntime | None = None
```

At runtime, after evidence is available:

- `prepare_route(...)` captures route reason.
- `assemble_bundle(...)` builds the canonical `PromptMessageBundle`.
- Trace prompt audit fields are filled from `PromptMessageBundle`.
- `AgentRuntime` may still reuse an internal `PromptSelection`, but `generate_answer(...)` receives only `prompt_bundle`.

### 3. Generator model-call behavior

`generate_answer(...)` now accepts:

```python
prompt_bundle: PromptMessageBundle | None = None
```

Changes:

- Deleted generator-owned `_build_model_messages`.
- Model calls now use `prompt_bundle.messages`.
- If `use_model=True` and a model call would occur without a prompt bundle, generation raises:

```text
prompt_bundle is required when use_model is true
```

- `answer.generation_trace` now includes:
  - `template_id`
  - `template_version`
  - `snapshot_id`
  - `route_reason`
  - `prompt_missing_variables`
  - `prompt_injection_summary`

All prompt metadata above now comes only from `PromptMessageBundle`.

No prompt full text is stored in `generation_trace`.

### 4. Test coverage added

- Model generation verifies prompt bundle driven tracing and bundle-required model calls.
- CLI pipeline integration verifies prompt trace fields are present and mirrored into `answer.generation_trace`.
- Contract test verifies new `RunTrace` prompt audit fields default correctly.

## Verification

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py tests/unit/core/test_contracts.py -q
```

Output:

```text
...........                                                              [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
................................                                         [100%]
```

## Result

Task 4 requirements are implemented within the requested file scope. Prompt assembly is now owned by `PromptRuntime`, model generation consumes canonical prompt bundles, and both runtime trace surfaces carry prompt audit metadata without leaking prompt body text.

## Review Fixes

- Added canonical `PromptMessageBundle.route_reason` in `src/prompts/asset_models.py`.
- `PromptAssembler` now copies `PromptSelection.route_reason` into the bundle during assembly.
- `PromptRuntime.assemble_bundle(...)` now accepts an optional precomputed `selection`, so `AgentRuntime` reuses one canonical `PromptSelection` for trace metadata and bundle assembly.
- `AgentRuntime` still uses `PromptSelection` internally for routing reuse, but trace fields and generation only consume bundle metadata.
- Removed `prompt_selection` from generator public function/method signatures and updated tests to pass only `prompt_bundle`.
- Added a rule-path regression test that proves generation still works with `use_model=False` and no `prompt_bundle`.
- Added a recording model-client test that verifies the exact `PromptMessageBundle.messages` are passed to the model client.

## Verification (Review Fix)

### Command 3

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_runtime.py tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
..............                                                           [100%]
```

### Command 4

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
...................................                                      [100%]
```

### Command 5

```powershell
rg -n "prompt_selection" src/generation.py src/generation src/agent tests/unit/generation tests/integration/app_loop tests/unit/prompts
```

Output:

```text
tests/unit/prompts\test_prompt_runtime.py:15:def test_prepare_route_returns_prompt_selection():
src/agent\runtime.py:72:                prompt_selection = self.prompt_runtime.prepare_route(
src/agent\runtime.py:85:                    selection=prompt_selection,
rg: src/generation.py: 系统找不到指定的文件。 (os error 2)
```

### Command 6

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_assembler.py tests/unit/prompts/test_prompt_runtime.py tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
....................                                                     [100%]
```

### Command 7

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/unit/generation/test_model_generation.py tests/integration/app_loop/test_cli_pipeline.py -q
```

Output:

```text
...................................                                      [100%]
```

## Review Fix: Terminal Action Prompt Audit Trace

### Finding

The PASS path returned the draft `AnswerEnvelope`, so prompt audit metadata from `PromptMessageBundle` stayed in `answer.generation_trace`. The terminal self-check paths `SAFE_RESPONSE`, `ASK_CLARIFICATION`, and `STOP` created replacement `AnswerEnvelope` objects and only wrote `agent_action` (plus `scene_binding` for clarification), which dropped:

- `template_id`
- `template_version`
- `snapshot_id`
- `route_reason`
- `prompt_missing_variables`
- `prompt_injection_summary`

### Fix

- Added a narrow `AgentActionExecutor` helper that extracts only the prompt audit fields above from `draft_answer.generation_trace`.
- Updated `terminal_answer(...)` to pass `draft_answer` into `safe_response(...)`, `scene_clarification(...)`, and `stop_response(...)`.
- The replacement terminal answers now keep prompt audit metadata and add `agent_action`.
- `ASK_CLARIFICATION` still preserves `scene_binding`.
- The helper does not copy prompt body text, full model messages, `plan_steps`, model provider details, fallback status, evidence ids, or output-length details from the replaced draft answer.
- `rewrite_only` remains a deepcopy of the draft answer and continues to preserve existing prompt audit metadata.

### Test Coverage

- Added app-loop assertions for `SAFE_RESPONSE` and `ASK_CLARIFICATION` verifying terminal `answer.generation_trace` prompt audit fields match `payload.trace`.
- Added assertions that terminal replacement answers do not contain full prompt/message fields or draft-only generation details such as `plan_steps`, `model_provider`, or `fallback_used`.

### Verification (Terminal Trace Fix)

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/integration/app_loop/test_agent_decision_execution.py tests/integration/app_loop/test_cli_pipeline.py tests/unit/generation/test_model_generation.py -q
```

Output:

```text
...........                                                              [100%]
```

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
..........................                                               [100%]
```

### Verification (Terminal Trace Signature Cleanup)

`stop_response(...)` now has one runtime signature:

```python
stop_response(draft_answer, check_report, scene_state)
```

The unsupported-decision terminal path in `AgentRuntime` passes the current draft answer into that method, so terminal replacement answers keep the same prompt audit trace path without a legacy/compatibility call shape.

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/integration/app_loop/test_agent_decision_execution.py tests/integration/app_loop/test_cli_pipeline.py tests/unit/generation/test_model_generation.py tests/unit/prompts -q
```

Output:

```text
.....................................                                    [100%]
```
