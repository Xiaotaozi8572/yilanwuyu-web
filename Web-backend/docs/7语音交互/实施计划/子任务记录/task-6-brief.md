# Task 6 实施简报：SpokenAnswerPlanner 与可取消流式 TTS

## 目标

仅从 T5 已验证的最终 `AnswerEnvelope` 与 `evidence_package_id` 派生短、可听、可审计的 `SpokenAnswer`，并通过统一 TTS Provider 返回真正可取消的 `PlaybackHandle`。取消完成后 Provider 不得继续生成，调用方也不得再获得或发送旧 chunk。

## 允许修改范围

- 新增 `src/voice/spoken_answer.py`
- 修改 `src/voice/tts.py`
- 必要时对 `src/voice/contracts.py`、`src/voice/providers.py` 做最小契约补强
- 新增 `tests/unit/voice/test_spoken_answer.py`
- 修改 `tests/unit/voice/test_voice_components.py`
- 与 Provider contract 直接相关的 voice 单测
- `docs/项目总控/STATUS.md`
- 本任务报告

本任务不接入 orchestrator、barge-in、WebSocket、P6/P2；接线留给 T7/T9。不得修改 RAG、生成器或原始 `AnswerEnvelope`。

## 必须先建立的失败测试

1. 确定性 planner 输出一句结论、最多 3 个解释步骤、最多 1 个追问，保留 `source_answer_id` 和非空 `source_evidence_package_id`，`new_claim_ids == ()`。
2. 字符预算、句数预算、估算时长预算都由 `VoiceSettings` 消费；超限必须实际压缩文本，不得只篡改 duration。安全说明要完整保留；若安全说明本身使预算不可满足，应明确失败而非静默截断。
3. 模型路径实际调用 `ModelClient.complete_structured()`，使用 `spoken_answer_style_prompt` 的真实 PromptRuntime bundle；模型不可用、schema/type/unknown-field 错误、超预算或输出不受原 AnswerEnvelope 支持时回退确定性 planner。
4. 模型/确定性结果均不得增加新事实。至少测试 unsupported claim、改变数字/实体、伪造追问；grounding 失败必须回退。
5. pronunciation lexicon 只改变交给 TTS 的读法/Mock 音频 payload，不得改变 `SpokenAnswer` 或 display answer。
6. Mock TTS 每个 spoken 片段产生一个 canonical `TTSChunk`；序号、duration、final 标记正确。
7. `PlaybackHandle.cancel(reason)` 原子、幂等；并发取消只生效一次；取消记录时间、已播放 chunk/时长/位置；取消返回后 chunks 立即结束，Provider 生成计数不再增加，旧 chunk 不再流向 transport。
8. 播放完成、Provider 异常、消费者提前关闭和取消竞态均无悬挂 asyncio task；TTS 失败保留 display/spoken 文本可用于文字降级，不触发 RAG。
9. raw audio 默认不落盘，异常、快照和 repr 不包含音频 payload、完整 Prompt 或密钥。

## 实现约束

- Planner 输入必须是 typed `AnswerEnvelope` 且 `source_evidence_package_id` 非空；不能从 partial transcript、Prompt 或模型常识构造事实。
- 确定性 fallback：结论取 `short_answer` 第一完整句；解释只取原 `main_answer`/已有 claim 支持的句子，最多三句；追问最多一个。
- spoken Prompt 必须真正执行，而非只记录 template metadata。模型输出仅允许 `answer_brief`、`spoken_steps`、`follow_up_prompt`。
- 结构校验和 grounding 采用保守策略；不能证明受原答案支持就 fallback。
- 安全说明优先于普通解释和追问，不允许被字符切片截断。
- `PlaybackHandle` 是 Provider 生成流与消费者之间的取消边界；只改 state 而不停止底层生成不算完成。
- 取消后不能再 yield 已缓存或新生成的旧音频 chunk；若实现后台 producer，必须可取消、await 并清空队列。
- 所有计时用单调时钟；不得持久化 Mock 音频 payload。

## 目标测试

```powershell
python -m pytest tests/unit/voice/test_spoken_answer.py tests/unit/voice/test_voice_components.py -q
```

完成后运行 voice unit、相关 Provider/配置回归、`compileall`、`git diff --check`，更新 `STATUS.md` 并独立提交。T6 虽非计划强制评审点，但主代理仍需做针对取消竞态与 grounding 的验收检查后才可进入 T7。
