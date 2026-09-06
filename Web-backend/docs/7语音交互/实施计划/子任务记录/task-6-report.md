# Task 6 实施记录：SpokenAnswerPlanner 与可取消流式 TTS

## 实施结果

- `SpokenAnswerPlanner` 只接收 typed `AnswerEnvelope` 和非空 `source_evidence_package_id`，保留 answer/evidence provenance，且 `new_claim_ids == ()`。
- 确定性路径只采用 `short_answer` 结论、已有 claim 支持的解释和完整安全说明；解释步数由 `VoiceSettings` 约束为 2–3。模型路径执行真实 PromptRuntime/ModelClient，schema、grounding、数字/实体、追问或预算校验失败时回退确定性 planner。
- 字符上限、估算时长、语速、解释步数、TTS Provider 和 pronunciation lexicon 均来自 `configs/voice.yaml`/`VoiceSettings`，没有竞争性业务默认常量。预算不满足时真实删除可选文本；安全说明或最小解释仍无法容纳时明确失败。
- `SpokenAnswer.source_evidence_package_id` 为必填非空字符串；TTS 边界再次校验 provenance，不能从无 evidence 的答案合成语音。
- pronunciation 在进入 TTS 时预应用，实际发音文本参与 duration 预算和 chunk duration 计算，但不会修改 `SpokenAnswer` 或 display answer。
- `PlaybackHandle` 采用 lazy pull 与单 chunk ack backpressure；分别记录 generated、delivered 和 transport 成功后的 played 指标。`mark_played()` 只在 transport 成功后推进播放位置。
- `cancel(reason)` 原子、幂等，并拥有/取消当前 encoder task；取消屏障在调用方自身被取消时仍会完成 Provider 停止与 `CANCELLED` 审计状态。取消返回后不再生成、交付或播放旧 chunk。
- TTS 异常转换为不含 spoken/payload 的 `VoiceTTSError`，原文字答案仍可降级显示，不重跑 RAG。

## TDD 与代码审查证据

1. 初始红测：模块尚不存在，目标测试收集阶段 `ModuleNotFoundError: voice.spoken_answer`，1 error，退出码 `2`。
2. 首轮主线预审红测：`3 failed`、退出码 `1`，分别证明 unsupported main sentence 进入 spoken、encode 未交付却计为已播放、空 stream config 绕过 settings duration。
3. 首轮独立审查发现 `1 Critical + 5 Important`，补充红测后 `7 failed`、退出码 `1`：默认零延迟流可能饿死取消、缺少 transport ack、调用参数可扩大配置上限、TTS provenance 未强制、契约 provenance 可空、最小解释数未落实、语速/解释步数仍为代码常量。
4. 中间取消与发音复核红测：`3 failed`、退出码 `1`，证明 pronunciation 后文本未参与时长门禁/chunk duration，且取消调用方被取消后句柄可能停留在 `CANCELLING`。即时 encoder 调度复核另有 `1 failed`、退出码 `1`。
5. 最终独立复审发现 `1 Critical + 1 Important`，红测 `3 failed`、退出码 `1`：阻塞 encoder 忽略 cancel event 时取消会永久等待；解释步数配置非法值 1/4 被接受。
6. 修复后 `PlaybackHandle` 显式拥有并取消 in-flight encoder，取消屏障等待任务和 generation lock；解释步数严格校验为 2–3。最终复审 `PASS`，Critical `0`、Important `0`。
7. 预算 fixture 最终采用 50 字符/8 秒，确保结论与两条受支持解释可容纳而追问需删除，未放宽生产断言。

## 最终验证

- `python -m pytest -o addopts='' -q tests/unit/voice/test_spoken_answer.py tests/unit/voice/test_voice_components.py`：`41 passed`，0 failed，0 skipped，退出码 `0`。
- `python -m pytest -o addopts='' -q tests/unit/voice`：`252 passed`，0 failed，0 skipped，退出码 `0`。
- `python -m pytest -o addopts='' -q tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop`：`27 passed`，0 failed，0 skipped，退出码 `0`。
- `python -m pytest -o addopts='' -q`：`442 passed`，0 failed，0 skipped，退出码 `0`。
- `python -m compileall -q src scripts`：退出码 `0`。
- `git diff --check`：退出码 `0`。
- 50 轮取消压力：每轮覆盖并发幂等取消、默认零延迟流取消、阻塞 encoder 强制终止，共 150 次检查；`pending_tasks=0`，结果 PASS，退出码 `0`。

## 边界检查

- 完成、Provider 异常、消费者提前关闭、取消调用方自身被取消和阻塞 encoder 竞态均无悬挂 asyncio task。
- `AudioFrame`/`TTSChunk` payload 不进入 repr；snapshot/error 只含 ID、Provider、状态、计数、位置和错误码，不包含 payload、完整 Prompt、私人 transcript 或密钥。
- T6 不接入 orchestrator、barge-in、P6/P2 或 WebSocket；由 T7/T9 按序接线。旧同步 TTS/MockVoiceLoop 仅保留至 T10/T11 调用方迁移完成后清理。
- 未接入真实 TTS、真实模型、密钥或生产服务。

## 评审结论

最终复审 PASS：Critical `0`、Important `0`。T6 的可信来源、配置唯一性、可取消性、transport ack、隐私和无任务泄漏门禁均满足，可以进入 T7。
