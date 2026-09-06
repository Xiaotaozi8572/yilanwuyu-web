### Task 6: Integrate Dedicated Voice Prompt Assets

**Files:**
- Modify: `src/voice/terminology.py`
- Modify: `src/input/voice_query_normalizer.py`
- Modify: `src/voice/tts.py`
- Modify: `src/voice/barge_in.py`
- Modify: `src/voice/voice_loop.py`
- Test: `tests/integration/voice_loop/test_mock_voice_loop.py`
- Test: `tests/e2e/scenarios/test_voice_flow.py`

**Interfaces:**
- Consumes: `PromptRuntime`.
- Produces: voice prompt usage in ASR correction, spoken answer conversion, and barge-in feedback parsing.

- [ ] **Step 1: Write voice prompt tests**

```python
# tests/integration/voice_loop/test_mock_voice_loop.py
from voice.voice_loop import MockVoiceLoop


def test_voice_loop_records_spoken_prompt_asset():
    result = MockVoiceLoop().run_turn("voice_prompt", "Explain wing", confidence=0.95)

    assert result.pipeline_response is not None
    assert result.tts_result is not None
    assert result.tts_result.metadata["prompt_template_id"] == "spoken_answer_style_prompt"


def test_asr_correction_uses_prompt_asset_metadata():
    result = MockVoiceLoop().run_turn("voice_asr", "C nine one nine wing", confidence=0.95)

    assert result.voice_query.metadata["asr_prompt_template_id"] == "asr_correction_prompt"
```

- [ ] **Step 2: Extend voice result models minimally**

Add `metadata: dict[str, str]` to `VoiceQueryObject` and `TTSResult`.

- [ ] **Step 3: Use prompt runtime in term correction**

`AviationTermCorrector` should accept `prompt_runtime: PromptRuntime | None`. In mock mode it still uses deterministic corrections, but records `asr_correction_prompt` as the prompt asset used.

- [ ] **Step 4: Use spoken answer prompt before TTS**

In `MockVoiceLoop`, assemble a prompt bundle for `spoken_answer_style_prompt` after the text answer passes the main pipeline. Use deterministic conversion in mock mode, but attach `prompt_template_id` and `prompt_version` to `TTSResult.metadata`.

- [ ] **Step 5: Use barge-in prompt metadata**

`BargeInController.handle_barge_in()` should classify feedback deterministically and attach `barge_in_feedback_prompt` to the produced `FeedbackEvent` metadata. If `FeedbackEvent` lacks metadata, add it as `metadata: dict[str, str]`.

- [ ] **Step 6: Run voice tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q
```

Expected: voice tests pass and voice prompt ids are visible in metadata.

- [ ] **Step 7: Commit**

```powershell
git add src/voice src/input/voice_query_normalizer.py tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py
git commit -m "feat: integrate voice prompt assets"
```

---

