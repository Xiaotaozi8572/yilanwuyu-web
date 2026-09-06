# Task 7 Report: Trace Export, Eval Assertions, and Legacy Cleanup

## Modified Files

- `src/observability/trace_exporter.py`
- `src/generation/generator.py`
- `scripts/run_eval.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- `tests/e2e/scenarios/test_text_qa_flow.py`
- `tests/integration/app_loop/test_agent_decision_execution.py`
- `tests/unit/core/test_contracts.py`
- `docs/superpowers/plans/_sdd/task-7-report.md`

## Implementation Notes

- `TraceReportExporter.export_markdown` now exports prompt audit metadata:
  `prompt_template_id`, `prompt_version`, `prompt_snapshot_id`, `prompt_route_reason`,
  `prompt_missing_variables`, and `prompt_injection_summary`.
- The markdown trace report still omits prompt bodies and model messages. It does not
  export `message.content`, rendered prompt text, prompt asset content, or full messages.
- The trace exporter sanitizes `prompt_injection_summary` before writing markdown, preserving
  only `section`, `chars`, and `present` even if future callers add raw prompt content fields.
- `scripts/run_eval.py` now requires prompt trace metadata for every smoke case that
  enters the main pipeline.
- Smoke eval case output now includes `prompt_template_id`, `prompt_version`,
  `prompt_snapshot_id`, and `prompt_route_reason`.
- The insufficient-evidence eval case checks:
  - `prompt_template_id == "aviation_basic_safe"`
  - `prompt_injection_summary` contains `section="prompt_asset"` with `present=True`
  - `answer.generation_trace["is_final_fact_prompt"] is False`
- `generation_trace` now includes the safe boolean provenance field
  `is_final_fact_prompt`; tests still reject prompt-body/full-message fields.
- E2E tests now assert trace/generation prompt metadata consistency and exported report
  absence of known prompt body snippets.
- `RunTrace` default-value coverage now explicitly includes `prompt_template_id`.

## Legacy Search

Command:

```powershell
rg -n 'PromptAssetStore|default_prompt_assets|_build_model_messages|role="context"|core\.contracts\.PromptAsset' src tests scripts
```

Result: no matches. `rg` returned exit code 1 because the search found zero results.

## Verification

Command:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/integration/app_loop tests/e2e/scenarios -q
```

Result:

```text
................................................                         [100%]
```

After tightening `is_final_fact_prompt` into a strict smoke-eval requirement and preserving it in terminal replacement answers, the broader target set was rerun:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/integration/app_loop tests/e2e/scenarios tests/unit/core/test_contracts.py -q
```

Result:

```text
....................................................                     [100%]
```

After adding trace-report summary sanitization, the full Task 7 target set was rerun:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/e2e/scenarios/test_eval_and_deployment_scripts.py tests/e2e/scenarios/test_text_qa_flow.py tests/integration/app_loop/test_agent_decision_execution.py tests/unit/core/test_contracts.py tests/unit/prompts tests/integration/app_loop tests/e2e/scenarios -q
```

Result:

```text
.....................................................                    [100%]
```

Command:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/core/test_contracts.py -q
```

Result:

```text
....                                                                     [100%]
```

Command:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
```

Result: exit code 0.

Command:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
```

Result: exit code 0; `pass_rate` was `1.0` with 3 of 3 cases passing. Each case
included main-pipeline prompt audit fields, and the insufficient-evidence case reported
`is_final_fact_prompt: false`.

Additional check:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id task7_trace_check
rg -n 'You are an aviation science tutor|You are preparing a spoken aviation tutoring answer|message\.content|rendered_prompt|prompt_body|full_prompt|messages' docs\trace_reports\task7_trace_check.md
```

Result: report exported successfully; leak search found no matches.

Final trace export check:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p9_trace_acceptance
```

Result: report exported successfully to `docs/trace_reports/p9_trace_acceptance.md`.
