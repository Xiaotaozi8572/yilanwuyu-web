### Task 7: Trace Export, Eval Assertions, and Legacy Cleanup

**Files:**
- Modify: `src/observability/trace_exporter.py`
- Modify: `scripts/export_trace_report.py`
- Modify: `scripts/run_eval.py`
- Modify: `tests/e2e/scenarios/test_text_qa_flow.py`
- Modify: `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- Modify: `tests/unit/core/test_contracts.py`

**Interfaces:**
- Consumes: extended `RunTrace`.
- Produces: trace reports and eval outputs that include prompt audit summaries without prompt full text.

- [ ] **Step 1: Add trace export assertion**

```python
# tests/e2e/scenarios/test_eval_and_deployment_scripts.py
from pathlib import Path


def test_trace_report_includes_prompt_audit_without_prompt_body():
    report = Path("docs/trace_reports/p9_trace_acceptance.md")
    if report.exists():
        text = report.read_text(encoding="utf-8")
        assert "prompt_template_id" in text
        assert "You are an aviation science tutor" not in text
```

- [ ] **Step 2: Update trace exporter**

Add prompt fields to the markdown report:

```python
lines.extend([
    f"- prompt_template_id: {trace.get('prompt_template_id')}",
    f"- prompt_version: {trace.get('prompt_version')}",
    f"- prompt_snapshot_id: {trace.get('prompt_snapshot_id')}",
    f"- prompt_route_reason: {trace.get('prompt_route_reason')}",
    f"- prompt_missing_variables: {trace.get('prompt_missing_variables')}",
    f"- prompt_injection_summary: {trace.get('prompt_injection_summary')}",
])
```

Do not include `message.content` or rendered prompt content.

- [ ] **Step 3: Update smoke eval**

In `scripts/run_eval.py`, assert each text or voice case that reaches the main pipeline has `trace.prompt_template_id`. For evidence-missing cases, assert `prompt_template_id == "aviation_basic_safe"` and `is_final_fact_prompt` appears false in the prompt injection summary.

- [ ] **Step 4: Delete legacy imports and tests**

Run:

```powershell
rg -n "PromptAssetStore|default_prompt_assets|_build_model_messages|role=\"context\"|core.contracts.PromptAsset" src tests scripts
```

Remove every production-code match. Test matches are only allowed when asserting the strings are absent.

- [ ] **Step 5: Run cleanup verification**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/integration/app_loop tests/e2e/scenarios -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit**

```powershell
git add src scripts tests docs/trace_reports
git commit -m "test: enforce prompt trace and remove legacy paths"
```

---

