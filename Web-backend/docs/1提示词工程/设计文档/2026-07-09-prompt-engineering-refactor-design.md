# Prompt Engineering Refactor Design

## Background

The current prompt engineering implementation has useful isolated pieces, but it is not the runtime authority for generation. `AgentRuntime` advances to `PROMPT_ROUTED` without selecting or assembling a prompt, and `GroundedAnswerGenerator` builds model messages on its own. This diverges from the Word design document, where prompt engineering owns template assets, runtime constraints, injection order, version governance, audit records, and voice prompt assets.

This refactor makes prompt engineering a first-class subsystem in the main RAG and Agent chain. It is not a patch. It replaces duplicated or partial paths with one authoritative prompt asset repository, one runtime prompt assembly path, and one governance service for promotion, rollback, evaluation, and audit.

## Goals

- Make Prompt Runtime the only source of model messages used by answer generation.
- Make Prompt Asset Repository the only source of prompt assets and versions.
- Make Prompt Governance Service the only path for promotion, deprecation, rollback, and evaluation records.
- Preserve the Word document boundary: RAG provides facts; prompt assets control teaching strategy, formatting, tone, safety boundary, and output contract.
- Remove redundant prompt contracts, fallback asset factories, and model-message construction paths.
- Support text, self-check, feedback rewrite, and voice prompts through the same asset model.
- Record prompt template id, version, snapshot id, route reason, missing variables, and injection summary in `RunTrace`.

## Non-Goals

- Do not introduce a heavy external prompt platform.
- Do not let prompt assets supply aviation facts.
- Do not keep compatibility shims for old prompt APIs once callers are migrated.
- Do not keep one-line placeholder prompt assets as production assets.
- Do not implement true online provider validation in this refactor.

## Required Architecture

### 1. Prompt Asset Repository

Create a repository-backed prompt asset system under `src/prompts/repository.py`. This replaces `PromptAssetStore` as the public asset access layer.

The repository loads a versioned directory structure:

```text
assets/prompts/
  aviation_explain_active/
    asset.yaml
    versions/
      v1.yaml
      v2.yaml
    evaluations/
      aviation_explain_active_v2.json
  aviation_basic_safe/
    asset.yaml
    versions/
      v1.yaml
      v2.yaml
    evaluations/
      aviation_basic_safe_v2.json
```

`asset.yaml` stores metadata and the active pointer. Each `versions/*.yaml` stores immutable version content and variable contract. Evaluation snapshots live next to the asset they validate.

The repository is responsible for:

- Loading all prompt assets and versions.
- Rejecting assets whose active version does not exist.
- Rejecting active versions without an approved evaluation snapshot.
- Returning only runtime-eligible assets to the router.
- Exposing all historical versions for rollback.
- Preventing direct activation outside governance.

### 2. Prompt Domain Models

Replace the current simplified dataclasses in `src/prompts/asset_models.py` with focused domain models:

- `PromptStatus`: `draft`, `candidate`, `active`, `experimental`, `deprecated`.
- `PromptVariableSpec`: `name`, `source`, `value_type`, `required`, `missing_behavior`, `description`.
- `PromptContentVersion`: `template_id`, `version`, `content`, `parent_version`, `change_reason`, `created_at`, `review_status`, `snapshot_id`, `variables`.
- `TeachingPromptAsset`: `template_id`, `title`, `task_type`, `concept_scope`, `scene_scope`, `status`, `active_version`, `activation_scope`, `constraints`, `risk_boundaries`.
- `PromptSelection`: selected template id, version, task type, status, route reason, missing variables, selected asset, selected version.
- `PromptMessageBundle`: model-ready messages, injection order, template id, version, snapshot id, injection summary, missing variables, final fact prompt flag.
- `PromptOptimizationRecord`: input version, output draft version, model, instruction, trigger event, created at.
- `PromptExample`: variables, RAG requirement, expected focus, failure modes, real output, score.
- `PromptEvaluationSnapshot`: test cases, dimension scores, violations, improvements, final decision.
- `CompareStopSignals`: target versus baseline, reference gap, overfit risk, stop recommendation.

Remove `core.contracts.PromptAsset` to avoid a second, weaker prompt model.

### 3. Prompt Runtime

Create `src/prompts/runtime.py` as the single entry point for runtime prompt use.

Responsibilities:

- Select the active or experimental prompt for the current intent and scope.
- Bind runtime variables from `QueryObject`, `SceneState`, `MemoryContext`, `EvidencePackage`, weak points, and output contract.
- Validate variable contracts before message assembly.
- Render `{{variable}}` placeholders with structured summaries, not raw objects.
- Enforce injection order from `configs/prompts.yaml`.
- Produce `PromptMessageBundle` containing `list[ModelMessage]`, not loose dicts.

The generated messages must use provider-compatible roles only:

- `system`: system boundary, safety boundary, output contract.
- `user`: task, scene summary, RAG evidence summary, memory expression guidance, prompt asset teaching strategy.

No runtime code may build model messages outside `PromptRuntime`.

### 4. Prompt Router

Keep `PromptRouter`, but narrow its responsibility. It only chooses a prompt asset and version. It does not load files, assemble messages, render variables, evaluate prompt quality, or activate versions.

Selection inputs:

- `QueryObject.intent_type`
- scene scope
- active or experimental runtime statuses
- activation scope
- required variable availability

Selection outputs:

- selected template id
- selected version
- route reason
- missing variables
- fallback or clarification requirement

When required fact variables such as `rag_evidence` are unavailable, the router must select an approved clarification prompt, not pretend a fact prompt is final.

### 5. Prompt Assembler

Refactor `PromptAssembler` to accept a selected prompt version and a validated variable map. It must not know how to find assets or gather runtime data.

The assembler must inject the rendered prompt content, not just `template_id`. It must also inject constraints and risk boundaries in a compact, auditable format.

The final injection order is:

1. System role and fact boundary.
2. Current task type.
3. Current MR/Web 3D scene state.
4. Local RAG evidence.
5. Learner memory and expression preferences.
6. Weak points and misconceptions.
7. Active prompt asset teaching strategy.
8. Output format and tool requirements.

RAG evidence must always appear before prompt asset content.

### 6. Generation Integration

`AgentRuntime` must call `PromptRuntime` twice:

1. Before retrieval, to reserve or preview prompt route after query understanding.
2. After retrieval and memory context preparation, to assemble the final `PromptMessageBundle`.

`GroundedAnswerGenerator.generate_answer()` must accept `prompt_bundle: PromptMessageBundle`.

When `use_model=True`, it must pass `prompt_bundle.messages` directly to `ModelClient.complete_structured()`.

When `use_model=False`, the rule-based generator may still generate from `EvidenceSketch`, but it must record `prompt_bundle.template_id`, `version`, and snapshot id in `generation_trace`.

Remove `_build_model_messages()` from `generation/generator.py`.

### 7. Prompt Governance Service

Create `src/prompts/governance.py`.

Responsibilities:

- Promote a candidate version to active only if variable validation, risk checks, and evaluation snapshot pass.
- Deprecate an active version when quality, factuality, or overfit risk fails.
- Roll back to an existing approved historical version.
- Store optimization records and evaluation snapshots.
- Record governance audit events.

Direct `activate_version()` must be removed from public use. The only state-changing API is governance.

Required governance APIs:

```python
promote_version(template_id: str, version: str, snapshot_id: str, reason: str) -> PromptGovernanceResult
deprecate_version(template_id: str, version: str, reason: str) -> PromptGovernanceResult
rollback_prompt(template_id: str, target_version: str, reason: str) -> PromptGovernanceResult
record_evaluation(snapshot: PromptEvaluationSnapshot) -> PromptGovernanceResult
```

Rollback must fail if the target version file or approved snapshot is missing.

### 8. Prompt Asset Content

Replace placeholder assets with complete templates:

- `aviation_fact_qa`: factual aviation Q&A.
- `aviation_component_explain`: component explanation with scene grounding.
- `aviation_concept_correction`: misconception correction.
- `aviation_quiz_reinforcement`: quiz and review.
- `aviation_feedback_rewrite`: controlled rewrite after user feedback.
- `aviation_self_check`: evidence, scene, memory, prompt, and safety check.
- `aviation_basic_safe`: evidence-missing clarification.
- `asr_correction_prompt`: aviation term correction for voice input.
- `spoken_answer_style_prompt`: short spoken answer conversion.
- `barge_in_feedback_prompt`: interruption intent parsing.

Each asset must include:

- real template text
- variable specs
- constraints
- risk boundaries
- active version pointer
- approved evaluation snapshot for active versions

No asset may include fixed aircraft facts or concrete values as template facts.

### 9. Voice Prompt Integration

Voice prompt assets must be used in these places:

- `asr_correction_prompt` in ASR term correction.
- `spoken_answer_style_prompt` before TTS synthesis.
- `barge_in_feedback_prompt` in barge-in feedback parsing.

Voice prompt assets must not directly answer aviation questions. They can normalize input, convert a checked answer to spoken form, or classify interruption feedback.

### 10. Trace and Audit

Extend `RunTrace` with:

- `prompt_template_id`
- `prompt_version`
- `prompt_snapshot_id`
- `prompt_route_reason`
- `prompt_missing_variables`
- `prompt_injection_summary`

The trace must never store full prompt text or raw private memory. The injection summary stores section names, source ids, variable names, token or character counts, and final/fallback flags.

`scripts/export_trace_report.py` must include the prompt audit summary.

### 11. Tests

Prompt tests must prove behavior at four layers:

- Repository: version loading, invalid asset rejection, approved snapshot enforcement, rollback target existence.
- Runtime: variable binding, missing variable handling, RAG before prompt injection, provider-compatible roles.
- Integration: `AppPipeline.run_text_query()` records prompt template id and passes prompt bundle to generation.
- Governance: candidate cannot promote without snapshot; rollback works with real version files; deprecated cannot route.

E2E and smoke eval must include prompt trace assertions.

## Files to Remove or Replace

- Remove `default_prompt_assets()` from `src/prompts/asset_store.py`.
- Replace `PromptAssetStore` with `PromptAssetRepository`.
- Remove `core.contracts.PromptAsset`.
- Remove public direct `activate_version()`.
- Remove dict/context-role message bundles.
- Remove `_build_model_messages()` from `src/generation/generator.py`.
- Remove tests that construct artificial in-memory rollback behavior without real version files.

## Migration Strategy

1. Introduce new domain models and repository.
2. Convert current assets into versioned directories.
3. Add real historical `v1` and current `v2` versions for existing active assets.
4. Add complete task and voice templates.
5. Replace router and assembler internals.
6. Add `PromptRuntime` and wire it into `AgentRuntime`.
7. Update `GroundedAnswerGenerator` to consume `PromptMessageBundle`.
8. Add governance service and replace direct activation tests.
9. Extend run trace and trace export.
10. Delete old redundant prompt interfaces and tests.

## Acceptance Criteria

- `AppPipeline().run_text_query(...)` returns trace data with non-empty `prompt_template_id`, `prompt_version`, and `prompt_injection_summary`.
- Model calls receive messages from `PromptRuntime`, not `GroundedAnswerGenerator`.
- `rg "default_prompt_assets|role=\"context\"|_build_model_messages|core.contracts.PromptAsset" src tests` has no production-code matches.
- Runtime prompt messages use only `system` and `user` roles.
- RAG evidence is injected before prompt asset content in every final fact prompt.
- Missing `rag_evidence` selects an approved clarification prompt and marks the bundle as not final fact prompt.
- Candidate, draft, and deprecated assets cannot route.
- Rollback from `v2` to `v1` succeeds for a real asset with real version files and approved snapshots.
- Rollback to a missing or unapproved version fails with a structured error.
- Voice flow uses dedicated ASR correction, spoken-answer, and barge-in prompt assets.
- Prompt full text is absent from normal trace output.
- Prompt unit tests, app integration tests, voice prompt tests, smoke eval, and compileall pass.

## Risks

- The project currently has encoding issues in some Chinese source strings. Prompt assets should be validated as UTF-8 before migration.
- The project uses a simple YAML parser. The new asset format must either stay within its supported subset or introduce a small, approved YAML parser. If no dependency is allowed, use JSON for version files and snapshots.
- The runtime chain is mock/offline-first. The refactor must keep local tests runnable without API keys.

## Self Review

- No placeholder implementation steps are left in this design.
- The design maps all nine identified mismatches to concrete architecture changes.
- The design keeps one prompt repository, one runtime path, and one governance path.
- The design removes redundant interfaces instead of preserving compatibility shims.
- The design preserves the Word document boundary: prompts decide how to teach, not what aviation facts are true.
