# Task 10 实施记录：Smoke Eval、部署验证与旧调用方迁移

## 实施结果

- `run_eval.py` 删除 `MockVoiceLoop` 导入，新增 `voice_realtime_mock` case。客户端向本地随机端口发送 1 个 speech PCM frame 和 30 个 endpoint silence frames，ASR partial/final 由 Provider registry 的统一 Mock Provider 产生。
- smoke 使用 reviewed aviation source、`RetrievalController` 和真实 `AppPipeline`；透明 recording proxy 只记录公共请求/响应，不复制检索、生成、自检或证据逻辑。
- smoke 验证 normalized query=`解释机翼`、`TextQueryRequest.source=voice`、完整 Prompt 审计四字段、唯一正式 pipeline 调用、可信 answer/evidence IDs、实际 `SpokenAnswer`、3 个 TTS chunks 和语音隐私指标。
- spoken 真实性门控覆盖 brief、2–3 个 supported/evidence-linked steps、follow-up、空 `new_claim_ids` 和 source IDs；任一 unsupported 内容、重复 RAG、事件裁剪、TTS/metrics 数量不一致都会令 case 失败。
- voice case 异常边界在 server 创建后立即进入 `try/finally`；scenario/frame/client 异常也会关闭 server、释放端口并 drain metrics。外层只输出稳定 `VOICE_REALTIME_SMOKE_FAILED/offline_voice_case_failure`，不包含异常原文。
- `smoke_eval.json` 改为同目录临时文件、flush/fsync 后 `os.replace` 原子写入；失败注入测试重定向到临时目录，不覆盖正式成功报告。
- `validate_deployment.py` 真实解析 `VoiceSettings`、两个词典和 Provider registry；校验 canonical 词典路径、非空映射、LLM/embedding 全离线 profile、`websockets==15.0.1` 依赖以及 distribution/runtime/asyncio API 三重 readiness。
- deployment 报告契约为独立 `voice_transport=websocket` 和 exact `voice_providers={vad: mock, asr: mock, tts: mock}`；任一配置、词典、Provider、离线路径或 WebSocket 条件不符均 `status=failed`、CLI 退出码 `1`。
- `test_voice_flow.py` 不再硬编码可信回答，改为真实 reviewed source -> retrieval -> AppPipeline -> P5 -> spoken/TTS E2E；低置信路径证明真实 pipeline 零调用。
- `test_mock_voice_loop.py` 文件名暂留到 T11，但内容已全部迁移到 PCM frame、流式 Mock ASR 和 orchestrator 公共行为；覆盖低置信、术语纠错、scene binding、partial-only final gate、Prompt trace、可信播放和指标。
- trace CLI 测试使用唯一临时 run ID 并在 `finally` 删除生成报告；tracked `e2e_trace.md` 保持 HEAD 零差异。

## TDD 与独立评审证据

1. 初始 T10 红测：`4 failed, 3 passed`，退出码 `1`。根因是旧 `voice_mock/MockVoiceLoop`、deployment 缺少语音能力字段及无 temp-root/version 失败注入接口。
2. deployment schema/词典评审红测：`5 failed, 7 passed`，退出码 `1`。修复 `voice_transport`/`voice_providers` 分离，并为 terminology/pronunciation 的空映射和畸形值输出独立稳定失败码。
3. 完整评审攻击红测：`14 failed, 10 passed`，退出码 `1`。覆盖非 mock LLM/embedding、越界 lexicon binding、WebSocket runtime/API、unsupported one-step spoken、重复 pipeline、缺 display/completed、TTS count 不一致、异常泄漏和非原子报告。
4. spoken 完整来源红测：unsupported brief 和 unsupported follow-up 均失败，修复后 brief 只来自 final short answer，follow-up 只允许原答案追问。
5. Prompt trace 红测：删除 version/snapshot/route 时 `3 failed, 3 passed`，修复为四个 `PROMPT_AUDIT_FIELDS` 均须非空字符串。
6. E2E 评审发现本地硬编码 `TextQueryResponse` 绕过真实 RAG/P5；改用 reviewed source 和真实 `AppPipeline` 后，可信 ID、Prompt trace、supported spoken 及低置信零调用均由公共链证明。
7. 报告副作用评审发现失败测试会覆盖正式 smoke；修复为 temp redirect，并将 trace subprocess 设为唯一 run ID + `finally` 删除。
8. 最终独立评审 PASS：Critical `0`、Important `0`。评审重复执行 smoke 5 次均 `pass_rate=1.0`，无悬挂 task、未释放端口或旧调用方引用。

## 最终验证

```powershell
python scripts/validate_deployment.py
python scripts/run_eval.py --suite smoke
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_eval_and_deployment_scripts.py tests/e2e/scenarios/test_voice_flow.py tests/integration/voice_loop
python -m pytest -o addopts='' -q tests/unit/voice tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop tests/e2e/scenarios/test_voice_websocket_flow.py tests/e2e/scenarios/test_voice_flow.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
python -m pytest -o addopts='' -q
python -m compileall -q src scripts
git diff --check
```

- deployment：`status=ok`，`failed_checks=[]`，LLM/embedding=`mock`，voice transport=`websocket`，VAD/ASR/TTS=`mock`，raw audio persistence=`false`，WebSocket distribution/runtime=`15.0.1`，退出码 `0`。
- smoke：`3/3`，`pass_rate=1.0`，voice case 3 个 TTS chunks、close code `1000`、port released=`true`、pending task count=`0`，退出码 `0`。
- 目标测试：`87 passed`，0 failed，0 skipped，退出码 `0`。
- 相关 voice/T8/T9/app/feedback 回归：`388 passed`，0 failed，0 skipped，退出码 `0`。
- 全量 pytest：`547 passed`，0 failed，0 skipped，退出码 `0`。
- compileall：退出码 `0`；`git diff --check`：退出码 `0`。
- `rg -n "MockVoiceLoop|from voice\.voice_loop" scripts tests`：零匹配。
- smoke 隐私扫描只命中允许字段 `private_payload_logged: false`，无原始音频、完整 transcript、Prompt 或私有 payload。
- `e2e_trace.md` 零差异；`t10_trace_*.md` 临时报告残留数为 `0`。

## 文件变更

### 新增

- `docs/7语音交互/实施计划/子任务记录/task-10-brief.md`
- `docs/7语音交互/实施计划/子任务记录/task-10-report.md`

### 修改

- `scripts/run_eval.py`
- `scripts/validate_deployment.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- `tests/e2e/scenarios/test_voice_flow.py`
- `tests/integration/voice_loop/test_mock_voice_loop.py`
- `docs/评测与验收/评测报告/smoke_eval.json`
- `docs/项目总控/STATUS.md`

### 删除

- 无。`src/voice/voice_loop.py` 和兼容 helper 只在 T11 零引用确认后删除。

## 状态、隐私与 Harness

- 全部语音调用方经唯一 orchestrator；WebSocket adapter 和测试未直接调用 retrieval internals、Generator、EvidenceStore 或 MemoryRepository。
- E2E 输入是连续 PCM bytes，不把 transcript 字符串冒充音频；partial/final 仅由统一 ASR Provider 输出。
- smoke 输出不持久化客户端 transcript 或音频内容，只记录安全事件类型、IDs、结构、计数和 false 隐私标志。
- deployment 使用 `env={}` 加载配置，不读取真实 API Key；未连接真实 ASR/TTS/模型、数据库或外部服务。
- 未新增依赖；仅验证已批准的 `websockets==15.0.1`。
- `AppPipeline.run_text_query()` 文本默认 source 行为和既有 CLI/API 回归保持通过。
- 未违反 `harness.md`，无待确认阻塞项。

## 未完成事项与后续范围

- T11 仍需删除 `src/voice/voice_loop.py`、旧同步 TTS/metrics 兼容路径、无引用 callback helper 和已迁移后保留的旧测试文件名，并完成最终零引用扫描。
- 真实 ASR、真实 TTS、真实模型、生产鉴权/TLS、媒体服务和生产部署不在本任务范围，当前只完成离线 Mock 实时原型。

## 结论

T10 最终独立评审 PASS（Critical `0`、Important `0`），调用方迁移、真实 PCM E2E、smoke 真实性、deployment readiness、隐私和测试副作用隔离均满足计划要求，可以进入 T11 死代码清理和最终验收。
