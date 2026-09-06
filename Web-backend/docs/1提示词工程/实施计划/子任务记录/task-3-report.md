# Task 3 Report: Refactor Router, Assembler, and Prompt Runtime

## Status

Completed.

## Scope

Implemented Task 3 in the requested prompt runtime surface:

- `PromptRouter` now only selects prompt assets and versions.
- `PromptRuntime.from_config("configs/prompts.yaml")` now constructs repository, router, assembler, and config.
- `PromptRuntime.prepare_route(...)` returns `PromptSelection`.
- `PromptRuntime.assemble_bundle(...)` performs runtime variable binding and returns canonical `PromptMessageBundle`.
- No second message bundle type was introduced.
- No `context` model role is emitted.
- `injection_summary` remains summary-only and does not store full prompt text.

## Files Changed

- `D:\APP\Python 3.13\挑战杯\src\prompts\router.py`
- `D:\APP\Python 3.13\挑战杯\src\prompts\assembler.py`
- `D:\APP\Python 3.13\挑战杯\src\prompts\runtime.py`
- `D:\APP\Python 3.13\挑战杯\src\prompts\__init__.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_router.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_assembler.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_runtime.py`

## Implementation Notes

### Router

- Moved router responsibility back to asset/version selection only.
- Missing required final-fact variables now route to `clarification_template_id` with:
  - `route_reason="missing_required_variables"`
  - `is_clarification=True`
- Candidate/draft/deprecated intent matches are treated as non-runtime-routable and raise `PromptStatusError` instead of silently falling through.

### Assembler

- `PromptAssembler` now accepts:
  - `PromptSelection`
  - bound `variables: dict[str, str]`
  - `injection_order`
- It renders prompt placeholders into the selected prompt content and emits exactly two `ModelMessage` instances:
  - one `system`
  - one `user`
- `PromptMessageBundle.injection_summary` only records per-section metadata (`section`, `chars`, `present`).

### Runtime

- Added `PromptRuntime` as the public runtime entry point for routing plus assembly.
- `assemble_bundle(...)` binds summaries from:
  - `query_object`
  - `scene_state`
  - `memory_context`
  - `evidence_package`
  - `output_contract`
  - `weak_points`
- Bound evidence is injected before prompt asset content in the assembled user message.

### Exports

- `src/prompts/__init__.py` now exports:
  - `PromptRuntime`
  - `PromptAssetRepository`
  - `PromptRouter`
  - `PromptAssembler`
  - prompt domain models and related errors/config

## Test Commands and Output

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_assembler.py tests/unit/prompts/test_prompt_runtime.py -q
```

Output:

```text
.......                                                                  [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
...................                                                      [100%]
```

## Constraints Check

- No changes were made to `agent`, `generation`, or `voice`.
- No old `PromptAssetStore`, flat YAML prompt assets, or compatibility shims were restored.
- No second message bundle type was introduced.
- No `context` role is emitted.
- No new dependencies were added.

## Concerns

- The original Task 3 router still used a global required-variable gate and private repository access; the review fix below corrects both behaviors.

## Task 3 Review Fix Addendum

### Review Fix Summary

- Router now selects the runtime-eligible asset/version before validating required variables.
- Required variables now come from the selected version's own `PromptVariableSpec` entries where `required=true`.
- Missing `clarification` variables now route to `clarification_template_id`.
- Missing `error` variables now raise a prompt variable error instead of silently rerouting.
- Router no longer reads repository private `_assets`; it uses the public `find_assets_for_intent(...)` repository method.
- Added regression coverage for `asr_correction`, `spoken_answer_style`, and `barge_in` routing without `rag_evidence`.

### Additional Files Changed

- `D:\APP\Python 3.13\挑战杯\src\prompts\repository.py`
- `D:\APP\Python 3.13\挑战杯\src\prompts\router.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_repository.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_router.py`
- `D:\APP\Python 3.13\挑战杯\tests\unit\prompts\test_prompt_runtime.py`

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_runtime.py -q
```

Output:

```text
...................                                                      [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
.........................                                                [100%]
```

## Task 3 Cleanup Addendum

Removed the stale global prompt gate config `required_final_fact_variables` from the live router config surface.

### Command 3

```powershell
rg -n "required_final_fact_variables" configs src tests docs/spec.md docs/harness.md
```

Output:

```text
```

### Command 4

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
.........................                                                [100%]
```
