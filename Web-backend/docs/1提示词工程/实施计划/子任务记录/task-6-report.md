# Task 6 Report: Integrate Dedicated Voice Prompt Assets

## Modified files

- `src/prompts/runtime.py`
- `src/input/voice_query_normalizer.py`
- `src/voice/terminology.py`
- `src/voice/tts.py`
- `src/voice/barge_in.py`
- `src/voice/voice_loop.py`
- `src/core/contracts.py`
- `tests/unit/prompts/test_prompt_runtime.py`
- `tests/unit/voice/test_voice_components.py`
- `tests/integration/voice_loop/test_mock_voice_loop.py`
- `tests/e2e/scenarios/test_voice_flow.py`

## Implementation notes

- Added `PromptRuntime.assemble_bundle_for_template(...)` for explicit template assembly through `repository.get_asset`, `repository.get_active_version`, and `PromptAssembler.assemble_messages`.
- Added metadata fields to `VoiceQueryObject`, `TTSResult`, and `FeedbackEvent` using `field(default_factory=dict)`.
- Added `TermCorrectionResult` and made `AviationTermCorrector` assemble `asr_correction_prompt` through `PromptRuntime` before deterministic term correction.
- Propagated ASR prompt provenance into `VoiceQueryObject.metadata` with `asr_prompt_template_id`, `asr_prompt_version`, and `asr_prompt_snapshot_id`.
- Made `MockVoiceLoop` assemble `spoken_answer_style_prompt` after the main pipeline response and before TTS, then attach prompt provenance to `TTSResult.metadata`.
- Made `BargeInController` assemble `barge_in_feedback_prompt` and attach prompt provenance to `FeedbackEvent.metadata` while preserving deterministic feedback classification.
- Voice tests now assert the full provenance triplet for prompt-backed voice paths: template id, version, and snapshot id.
- Did not edit `src/prompts/governance.py` or `src/prompts/repository.py`.

## Test commands and output

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_runtime.py tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q
```

Output:

```text
...................                                                      [100%]
```

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
...................................                                      [100%]
```

After review, provenance assertions were strengthened and the combined prompt/voice suite was rerun:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_runtime.py tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py tests/unit/prompts -q
```

Output:

```text
................................................                         [100%]
```

## Remaining risks

- No known failing tests in the requested scope.
- Prompt runtime assembly is deterministic and only stores bundle provenance metadata, not prompt message content.
