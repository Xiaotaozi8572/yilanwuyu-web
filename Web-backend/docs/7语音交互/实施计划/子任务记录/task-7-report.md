# Task 7 实施记录：barge-in、P6 改写与 P2 候选治理

## 实施结果

- `VoiceSessionOrchestrator` 成为播放、barge-in、P6/P2 分流和 session 状态的唯一入口；先原子取消并等待 sender 终止，再记录事件和解析反馈。
- STOP 不检索；SIMPLIFY/SHORTEN 锁定原 evidence 并走正式 P6 parser/planner/rewriter；FACT_CHALLENGE 和唯一场景重绑通过 `AppPipeline` 重新取证；长期偏好通过 P2 `MemoryController` 治理。
- Spoken 改写只使用 checkpoint 中已校验的 claim/evidence/source binding，保留 source answer/evidence ID。无法在配置的最小解释数下真实缩短时返回澄清，不伪造 duration 或 rewritten 状态。
- 同步 `AppPipeline` 与 P2 planner/controller 在 worker thread 执行；async 实现直接 await。P2 完整 record→process 由 orchestrator 专用 async lock 串行化，因此共享非事务化控制器不跨 session 重叠，同时不阻塞其他 session 的 RAG。
- 播放替换、finish、barge-in、正常完成和 transport 失败均回收 handle/sender/finalizer；调用方取消不能中止核心 cancel-before-parse 序列。
- Feedback checkpoint 严格绑定 user/session/turn，并校验 answer、evidence package、P5 check report、claim/evidence/source binding；finish、STOP 和新取证替换时受控释放。
- barge-in enabled/priority、偏好 expiry 均实际消费配置。无效 expiry 或治理异常安全进入 CLARIFY，不启动新 TTS。

## TDD 与评审证据

1. 初始行为/硬化套件：`9 failed, 10 passed`，退出码 `1`。失败覆盖真实 spoken planner 未改写、finish 未停播、sender 生命周期未回收、调用方取消破坏一致性、同步 pipeline 阻塞、checkpoint 完整性缺失、配置门控缺失和无效 expiry 仍写 P2。
2. 真实 planner 红测：SHORTEN 前后 spoken 长度为 `45 == 45`；SIMPLIFY 仍包含原术语。修复后 projection 只由锁定 claim 派生；新增无法安全缩短时 CLARIFY 的不变量测试。
3. 首轮独立评审：`1 Critical + 7 Important`。发现重复 start 可并发发送、finish/发送完成/失败未回收、barge core 可被调用方取消、sync pipeline 阻塞、spoken 伪改写、checkpoint binding 不完整、barge 配置未生效。C1 原测试因 `asyncio.run()` teardown 假通过，纠正后精确复现旧 handle 仍 `PLAYING`。
4. 二轮独立评审：`1 Important`。修正探针后慢 P2 治理造成 `0.201s` event-loop 延迟；治理异常直抛；callback-before-stop 可残留 ownership set。修复为 async helper + `to_thread`，异常安全 CLARIFY，并显式 settle finalizer 竞态。
5. 最终终审：`1 Important`。两个并发 PREFERENCE 使共享 P2 record→process `overlap=True`。orchestrator 专用 `_preference_governance_lock` 覆盖完整 plan 后 `overlap=False`；锁等待不阻塞 event loop，另一 session 仍 answered。
6. 最终独立复审：PASS，Critical `0`、Important `0`。

## 最终验证

- 目标命令：

  ```powershell
  & "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q tests/integration/voice_loop/test_barge_in_feedback.py tests/unit/voice tests/unit/feedback tests/integration/feedback_loop tests/integration/app_loop tests/e2e/scenarios/test_feedback_flow.py
  ```

  结果：`309 passed`，0 failed，0 skipped，退出码 `0`。

- 全量命令：

  ```powershell
  & "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q
  ```

  结果：`470 passed`，0 failed，0 skipped，退出码 `0`。

- `python -m compileall -q src scripts`：退出码 `0`。
- `git diff --check`：退出码 `0`。
- P2 两并发治理：`overlap=False`。
- 慢同步 P2 治理：event-loop probe 小于 60 ms；另一 session 为 answered。
- 25 轮 callback-before-stop 压力：ownership set `0`，pending playback/finalizer task `0`。
- `smoke_eval.json` 和 `e2e_trace.md` 的测试生成变更已恢复，不纳入 T7 提交。

## 边界与后续清理

- 未接入真实 ASR、真实 TTS、真实模型、密钥、生产数据库或生产服务；未新增依赖。
- 语音事实不直接访问检索内部、Generator、MemoryRepository 或 EvidenceStore；P6/P2 只走公共接口。
- `SIMPLIFY` 的确定性术语释义须在 T11 前统一迁移到既有 terminology lexicon 配置源，并以配置消费测试保护后删除代码映射。
- 普通文本 checkpoint 的有界生命周期须在 T11 前通过既有生命周期信号或正式配置完成；禁止硬编码容量、静默逐出或破坏文本 P6 契约。
- 旧 `MockVoiceLoop` 仅在 T10 完成调用方迁移后由 T11 零引用删除。

## 结论

T7 最终评审 PASS。barge-in 顺序、P6/P2 公共接线、可信来源、播放取消与回收、跨 session P2 并发安全均满足任务要求，可以进入 T8。
