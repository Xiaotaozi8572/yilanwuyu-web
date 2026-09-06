# Trace Report: e2e_trace

- current_state: FINAL_READY
- action_decision: PASS
- final_answer_id: answer_80326917ff22
- evidence_ids: none
- prompt_template_id: aviation_basic_safe
- prompt_version: v2
- prompt_snapshot_id: aviation_basic_safe_v2
- prompt_route_reason: missing_required_variables
- prompt_missing_variables: ['rag_evidence']
- memory_context_ids: memctx_930d00770038
- prompt_injection_summary: [{'section': 'system_boundary', 'chars': 91, 'present': True}, {'section': 'task_type', 'chars': 13, 'present': True}, {'section': 'rag_evidence', 'chars': 0, 'present': False}, {'section': 'scene_state', 'chars': 0, 'present': False}, {'section': 'memory_context', 'chars': 0, 'present': False}, {'section': 'weak_points', 'chars': 0, 'present': False}, {'section': 'prompt_asset', 'chars': 324, 'present': True}, {'section': 'output_contract', 'chars': 27, 'present': True}]

## Transitions
- INPUT_RECEIVED -> PROMPT_ROUTED: query understood and prompt boundary reserved
- PROMPT_ROUTED -> MEMORY_CONTEXT_READY: memory context retrieved
- MEMORY_CONTEXT_READY -> RETRIEVAL_PLANNED: retrieval plan created
- RETRIEVAL_PLANNED -> EVIDENCE_READY: weak
- EVIDENCE_READY -> ANSWER_DRAFTED: missing_or_weak_evidence
- ANSWER_DRAFTED -> SELF_CHECKED: self check completed
- SELF_CHECKED -> FINAL_READY: PASS terminal response ready

## Errors
- none
