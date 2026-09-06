# Trace Report: run_hash_ce9a1ed86556aef2

- current_state: FINAL_READY
- action_decision: PASS
- final_answer_id: answer_eca12dc794a5
- evidence_ids: none
- prompt_template_id: template_hash_f9c43c0c760abd8e
- prompt_version: v2
- prompt_snapshot_id: snapshot_hash_89ead6a7547534bf
- prompt_route_reason: missing_required_variables
- prompt_missing_variables: ['rag_evidence']
- memory_context_ids: memctx_ee6e77213347
- prompt_injection_summary: [{'section': 'system_boundary', 'chars': 91, 'present': True}, {'section': 'task_type', 'chars': 13, 'present': True}, {'section': 'rag_evidence', 'chars': 0, 'present': False}, {'section': 'scene_state', 'chars': 0, 'present': False}, {'section': 'memory_context', 'chars': 0, 'present': False}, {'section': 'weak_points', 'chars': 0, 'present': False}, {'section': 'prompt_asset', 'chars': 324, 'present': True}, {'section': 'output_contract', 'chars': 27, 'present': True}]
- voice_trace_summary: {}

## Transitions
- INPUT_RECEIVED -> PROMPT_ROUTED: redacted
- PROMPT_ROUTED -> MEMORY_CONTEXT_READY: redacted
- MEMORY_CONTEXT_READY -> RETRIEVAL_PLANNED: redacted
- RETRIEVAL_PLANNED -> EVIDENCE_READY: weak
- EVIDENCE_READY -> ANSWER_DRAFTED: missing_or_weak_evidence
- ANSWER_DRAFTED -> SELF_CHECKED: redacted
- SELF_CHECKED -> FINAL_READY: redacted

## Errors
- none
