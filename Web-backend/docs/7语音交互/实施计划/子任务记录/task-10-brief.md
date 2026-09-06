# Task 10 实施简报：Smoke Eval、部署验证与旧调用方迁移

## 目标

将 smoke、deployment validation、旧语音 E2E 和旧集成测试全部迁移到唯一 `VoiceSessionOrchestrator` 路径。语音测试必须发送连续 PCM `AudioFrame`，由 registry 创建 Mock ASR partial/final，不得再把 transcript 字符串当作音频，也不得保留 `MockVoiceLoop` 调用方。

## 实施范围

- 将 `scripts/run_eval.py` 的语音 case 迁移为 `voice_realtime_mock`，经本地随机端口、`MockVoiceClient`、WebSocket adapter 和唯一 orchestrator 执行。
- 用真实 reviewed source、`RetrievalController` 和 `AppPipeline` 验证 normalized final query、Prompt trace、P5 可信 answer/evidence、spoken projection、TTS chunk 与语音指标。
- 强制 smoke 的正式 pipeline 请求/响应各一次，完整验证 spoken brief/steps/follow-up 不增加事实、source ID 一致、协议事件顺序与 TTS/metrics 数量一致。
- voice case 异常必须关闭 server、释放端口、收口 tasks，并输出稳定失败 case；报告使用同目录临时文件和原子替换。
- 增强 deployment validation：解析 `VoiceSettings`、两个非空词典、canonical 词典绑定、Provider registry、完整离线 LLM/embedding profile、`pyproject.toml` 依赖及 WebSocket distribution/runtime/API。
- 将 `test_voice_flow.py` 改为真实 `AppPipeline` E2E；将旧 `test_mock_voice_loop.py` 内容改为真实 PCM、流式 ASR、术语纠错、scene、低置信和 final gate 测试。
- 测试生成的失败 smoke 和 trace 报告使用临时路径或唯一 run ID，并在结束时清理，正式目录只保留成功 `smoke_eval.json`。

## 架构不变量

1. 所有 Mock、WebSocket 和测试输入进入同一个 `VoiceSessionOrchestrator`，不建立测试专用业务链。
2. 正式语音事实回答只经 `AppPipeline`、evidence package、生成和 P5 自检；partial、Prompt、用户反馈或模型常识不能成为事实来源。
3. 所有语音 E2E 输入均为 speech PCM 帧加 endpoint silence；transcript 只由 registry 的 Mock ASR Provider 产生。
4. smoke 的 spoken brief、steps、follow-up、answer ID 和 evidence package ID 必须逐项受最终 `AnswerEnvelope` 约束。
5. deployment validation 不读取环境密钥，不连接真实 Provider；LLM 和 embedding profile 必须都是 `mock`。
6. `voice_transport` 与 `voice_providers` 契约分离；后者只包含 `vad/asr/tts`。
7. 词典文件必须存在、可解析、非空，且配置解析后的路径严格绑定当前项目根目录中的 canonical 文件。
8. `websockets` distribution 版本、运行时模块版本和 asyncio server/client API 必须同时满足 `15.0.1`。
9. T10 只迁移调用方，不删除 `src/voice/voice_loop.py`；删除和兼容辅助路径清理由 T11 在零引用证明后执行。

## 验收命令

```powershell
python scripts/validate_deployment.py
python scripts/run_eval.py --suite smoke
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_eval_and_deployment_scripts.py tests/e2e/scenarios/test_voice_flow.py tests/integration/voice_loop
python -m pytest -o addopts='' -q tests/unit/voice tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop tests/e2e/scenarios/test_voice_websocket_flow.py tests/e2e/scenarios/test_voice_flow.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
python -m pytest -o addopts='' -q
python -m compileall -q src scripts
git diff --check
rg -n "MockVoiceLoop|from voice\.voice_loop" scripts tests
```

完成标准：deployment `status=ok`，smoke `3/3` 且 `pass_rate=1.0`；目标测试 `87 passed`、相关回归 `388 passed`、全量 `547 passed`，退出码均为 `0`；compileall/diff 退出码 `0`；独立评审 Critical `0`、Important `0`；`scripts/tests` 中旧调用方零匹配。
