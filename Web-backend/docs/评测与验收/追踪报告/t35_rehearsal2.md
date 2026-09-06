# Trace Report: run_hash_831f146567d1f1cc

- current_state: FINAL_READY
- action_decision: PASS
- final_answer_id: answer_3865fbc5d4df
- evidence_ids: evidence_hash_a2c58f700e6e9553, evidence_hash_f979dbd528728447, evidence_hash_b2855b3ffc6cf4eb, evidence_hash_bbcd9e877aeb5966, evidence_hash_f10f53799a5cc762, evidence_hash_8cf24a895a169403, evidence_hash_a5558bb1d05d9b9e, evidence_hash_88cbe1040da86622, evidence_hash_026d1531850f0312, evidence_hash_4f9fb0812c102518
- prompt_template_id: template_hash_5f82c25860050863
- prompt_version: v2
- prompt_snapshot_id: snapshot_hash_facf5f9ab5e40f01
- prompt_route_reason: redacted
- prompt_missing_variables: []
- memory_context_ids: memctx_34132472a83a
- memory_status: DEGRADED
- memory_diagnostic_code: MEMORY_TIMEOUT
- prompt_injection_summary: [{'section': 'system_boundary', 'chars': 383, 'present': True}, {'section': 'task_type', 'chars': 19, 'present': True}, {'section': 'scene_state', 'chars': 0, 'present': False}, {'section': 'rag_evidence', 'chars': 851, 'present': True}, {'section': 'memory_context', 'chars': 0, 'present': False}, {'section': 'weak_points', 'chars': 27, 'present': True}, {'section': 'prompt_asset', 'chars': 4420, 'present': True}, {'section': 'output_contract', 'chars': 3096, 'present': True}]
- voice_trace_summary: {}

## Transitions
- INPUT_RECEIVED -> PROMPT_ROUTED: redacted
- PROMPT_ROUTED -> MEMORY_CONTEXT_READY: redacted
- MEMORY_CONTEXT_READY -> RETRIEVAL_PLANNED: redacted
- RETRIEVAL_PLANNED -> EVIDENCE_READY: redacted
- EVIDENCE_READY -> ANSWER_DRAFTED: redacted
- ANSWER_DRAFTED -> SELF_CHECKED: redacted
- SELF_CHECKED -> FINAL_READY: redacted

## Errors
- none

## RAG Audit Summary
- unavailable
