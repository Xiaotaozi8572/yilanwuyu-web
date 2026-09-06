# 真实语音交互专项 · 约束规范（harness.md）

> 来源：`tmp/voice_real_plan_final.md`（v2 最终版）拆解
> 配套文档：`task.md`（任务分解）、`spec.md`（执行步骤）
> 编号约定：T0–T10，与 task.md/spec.md 一一对应。每任务的"允许文件清单""回滚规则""验证检查项""禁止项""单次提交边界"逐项列出。
> 全局原则：不执行任何 Git 操作（由用户手动提交）；AI 不自行修改安全边界；改动前确保可回滚；禁止引入授权外依赖；禁止改变既有架构模式。

---

## 全局约束（适用所有任务）

### G1 禁止项（硬红线）
- 禁止引入授权专节（T0）批准以外的依赖（仅 faster-whisper<2、edge-tts<7 及其传递依赖）。
- 禁止改变既有架构模式：`ASRProvider`/`TTSProvider` Protocol、`ProviderRegistry` 工厂、`PlaybackHandle`、`AppPipeline.run_text_query()` 公共边界、`TextQueryRequest`/`TextQueryResponse.to_dict()`/CLI 参数。
- 禁止 voice 模块绕过 AppPipeline 直接回答航空事实。
- 禁止把低置信 ASR 文本送入 RAG 正式检索。
- 禁止真实密钥、生产数据库、需密钥云 ASR/TTS、真实模型服务接入主链路。
- 禁止硬编码核心业务规则、阈值、provider 名、模型名（须配置化）。
- 禁止默认持久化原始音频（raw_audio_persist_enabled 必须为 False）。
- 禁止日志/trace/异常包含原始音频、完整私人 transcript、完整 Prompt、密钥。
- 禁止执行 Git commit/push/merge/reset/checkout。
- 禁止越权修改本任务 allowlist 外的文件。

### G2 回滚规则
- 每个任务完成后，由用户手动 Git 提交，形成独立回滚点。
- AI 不执行 Git，但须在任务完成时明确告知用户"可提交回滚点"。
- 配置级回滚：`configs/voice.yaml` 改回 mock provider 即可恢复。
- 依赖级回滚：移除 pyproject.toml 依赖 + 重新 uv lock。
- 文件级回滚：删除新增的 whisper_asr.py/edge_tts.py/run_voice.py/run_frontend.py/index.html。
- 单任务失败须先修复，不得带失败进入下一任务（AGENTS.md 第7条）。

### G3 验证检查通用项
- 每步改动后须通过：相关单测 + `python -c` 导入检查 + grep 残留检查。
- 治理类任务（T0/T1）须通过文档检索验证。
- 自动停止条件：同一测试连续三次失败且无法定位 → 停止并在 STATUS.md 记录"待确认"。

---

## T0 · 授权专节入档

### 允许文件清单
- `docs/项目总控/task.md`（仅追加，用户手动）

### 回滚规则
用户在入档前可撤回草案；入档后若反悔，由用户手动删除该专节。

### 验证检查项
- task.md 含"真实语音 Provider 专项：用户授权（2026-08-13）"标题。
- 专节含依赖白名单（faster-whisper<2、edge-tts<7）。
- 专节含新增文件清单（run_voice.py/run_frontend.py/index.html）。
- 专节含 edge-tts 三项知情决策。

### 禁止项
- AI 不得自行写入此专节（必须用户手动）。
- 不得修改 task.md 既有 P0–P8/G0–G8/LangGraph/M0–M5 历史记录。

### 单次提交边界
本任务 = 追加授权专节一次提交。不与其他改动混合。

---

## T1 · 治理文档更新

### 允许文件清单
- `docs/项目总控/harness.md`
- `docs/项目总控/STATUS.md`
- `docs/项目总控/AUTO_DEV.md`

### 回滚规则
用户提交前可对比 diff；提交后回滚需 revert 该提交。

### 验证检查项
- harness.md 不再含"严禁真实 ASR/TTS"针对本专项的禁令（保留对需密钥云服务的禁止）。
- harness.md 含 faster-whisper<2、edge-tts<7 依赖白名单。
- STATUS.md 含 2026-08-13 决策记录。
- AUTO_DEV.md 含本专项执行规则节。

### 禁止项
- 不得解除"需密钥云 ASR/TTS/生产数据库/真实模型服务"的禁令。
- 不得修改 P0–P8/G0–G8/LangGraph/M0–M5 既有门禁。
- 不得改动既有隐私硬约束。

### 单次提交边界
本任务 = 三文档更新一次提交。

---

## T2 · 依赖声明与环境核验

### 允许文件清单
- `pyproject.toml`
- `uv.lock`
- `docs/项目总控/STATUS.md`（核验记录）

### 回滚规则
移除 pyproject.toml 两行 + `uv lock` 恢复。

### 验证检查项
- pyproject.toml 含 faster-whisper>=1.0.3,<2、edge-tts>=6.1.0,<7。
- 指定 3.11.9 环境 `uv sync` 成功。
- `python -c "import faster_whisper, edge_tts"` 成功。
- STATUS.md 含核验记录。
- 未引入授权外依赖（grep pyproject.toml 无其他新增）。

### 禁止项
- 不得修改既有依赖版本。
- 不得引入 LangChain/LangSmith/向量库/云服务连接。
- 不得跳过 3.11.9 环境核验。

### 单次提交边界
本任务 = pyproject.toml + uv.lock + 核验记录一次提交。

---

## T3 · 配置扩展

### 允许文件清单
- `configs/voice.yaml`
- `src/voice/settings.py`

### 回滚规则
voice.yaml 改回 mock + 删除 asr_config/tts_config；settings.py revert 新字段。

### 验证检查项
- VoiceSettings.from_file('configs/voice.yaml') 成功且 asr_provider=="whisper"。
- asr_low_confidence_threshold==0.40。
- 未知字段仍被拒（测试覆盖）。
- 隐私硬约束不变（raw_audio_persist_enabled=False 等）。
- settings 单测全绿（含新字段快照断言）。

### 禁止项
- 不得引入凭据字段（API key/secret/endpoint）。
- 不得改动既有字段语义。
- 不得放宽隐私约束。
- 不得在此任务引用未实现的 with_default_providers（T6 才落地，本步 settings 内部仍用旧方法或先注释）。

### 单次提交边界
本任务 = voice.yaml + settings.py 一次提交。

---

## T4 · ASR Provider 实现

### 允许文件清单
- `src/voice/whisper_asr.py`（新增）
- `tests/unit/voice/test_whisper_asr.py`（新增）

### 回滚规则
删除两个新增文件即可（无既有文件改动）。

### 验证检查项
- WhisperASRProvider.name=="whisper"。
- 实现 ASRProvider Protocol（transcribe 方法）。
- 模型模块级单例缓存存在（_MODEL_CACHE）。
- 推理用 asyncio.Lock 串行化。
- 懒加载：import voice.whisper_asr 不触发模型下载。
- PCM int16→float32 转换正确。
- 置信度映射含 no_speech_prob 处理。
- 纯逻辑单测（stub 模型）全绿。
- 异常路径抛 VoiceASRError。

### 禁止项
- 不得修改 contracts.py（AudioFrame/ASRResult 不改）。
- 不得修改 asr.py（MockASRProvider 在 T6 改）。
- 不得修改 orchestrator.py（接线在 T6）。
- 不得在构造函数加载模型（必须懒加载）。
- 不得硬编码 model_size/device（从 settings 读）。
- 不得引入除 faster-whisper 外的新依赖。

### 单次提交边界
本任务 = 两个新增文件一次提交。

---

## T5 · TTS Provider 实现

### 允许文件清单
- `src/voice/edge_tts.py`（新增）
- `tests/unit/voice/test_edge_tts.py`（新增）

### 回滚规则
删除两个新增文件即可。

### 验证检查项
- EdgeTTSProvider.name=="edge"。
- 实现 TTSProvider Protocol（synthesize_stream 方法）。
- 构造函数与 MockTTSProvider 对称（settings + pronunciation_lexicon）。
- 一个 segment 产出一个完整 MP3 blob（一个 TTSChunk）。
- 复用 PlaybackHandle（不重写）。
- mock 单测全绿（chunk 非空、一 segment 一 chunk、取消生效、失败置 FAILED）。

### 禁止项
- 不得重写 PlaybackHandle。
- 不得新增 TTS 失败降级代码（既有已实现，T7 验收）。
- 不得修改 tts.py/contracts/orchestrator。
- 不得分 MP3 块下发（必须一 segment 一完整 blob）。
- 不得硬编码 voice/rate/volume（从 settings 读）。
- 不得引入除 edge-tts 外的新依赖。

### 单次提交边界
本任务 = 两个新增文件一次提交。

---

## T6 · Provider 注册与接线

### 允许文件清单
- `src/voice/providers.py`
- `src/voice/asr.py`
- `src/voice/orchestrator.py`
- `src/voice/settings.py`
- `scripts/validate_deployment.py`
- `tests/unit/voice/test_provider_registry.py`

### 回滚规则
revert 该提交（providers 重命名+接线是集中点）。

### 验证检查项
- `grep -rn with_mock_defaults src tests scripts` 无结果。
- with_default_providers 注册 mock+whisper+edge。
- orchestrator.py:1149 ASR 创建传 settings。
- MockASRProvider 接受 settings 参数。
- 全部调用方更新（含 validate_deployment.py:152、test_provider_registry.py）。
- `pytest tests/unit/voice tests/integration/voice_loop` 全绿。
- 未新增降级代码（grep 确认无新增 transport 错误事件逻辑）。

### 禁止项
- 不得保留 with_mock_defaults 别名（无冗余）。
- 不得新增 TTS 降级实现。
- 不得修改 PlaybackHandle/contracts/transport。
- 不得越权修改 allowlist 外文件。
- 不得改变 ASRProvider/TTSProvider Protocol 签名。

### 单次提交边界
本任务 = 注册重命名+接线+调用方同步一次提交。

---

## T7 · 测试与标定

### 允许文件清单
- `pyproject.toml`（仅 marker 注册）
- `tests/integration/voice_loop/test_tts_failure_degradation.py`（新增）
- `docs/项目总控/STATUS.md`（标定记录）

### 回滚规则
删除新增测试 + revert marker 行。

### 验证检查项
- pyproject.toml 注册 integration marker。
- test_tts_failure_degradation.py 全绿（断网降级链路）。
- 置信度标定：20 句 ≥80% 过门禁，结果写入 STATUS.md。
- VAD 标定记录写入 STATUS.md。
- 真实语音验收指标记录（首响延迟等）。

### 禁止项
- 不得为评测通过修改业务逻辑（AGENTS.md 第9条）。
- 不得放宽断言或关闭安全检查以通过测试。
- 不得伪造标定数据。
- 不得修改 allowlist 外的业务代码。

### 单次提交边界
本任务 = marker + 降级测试 + 标定记录一次提交。

---

## T8 · 语音入口

### 允许文件清单
- `scripts/run_voice.py`（新增）
- `src/voice/websocket_server.py`（仅 serve_voice 签名微调，若需接受外部 settings）

### 回滚规则
删除 run_voice.py + revert websocket_server 签名调整。

### 验证检查项
- `python scripts/run_voice.py` 可运行并打印端口。
- 绑定 127.0.0.1（loopback）。
- 复用 serve_voice，不重复 WS 逻辑。
- 不改动 websocket_server 既有协议。

### 禁止项
- 不得绑定非 loopback 地址（须 127.0.0.1）。
- 不得重复实现 WS server 逻辑。
- 不得改动 websocket_server 的二步成帧协议。
- 不得引入新依赖。

### 单次提交边界
本任务 = run_voice.py + websocket_server 微调一次提交。

---

## T9 · 前端页面

### 允许文件清单
- `scripts/run_frontend.py`（新增）
- `assets/voice/index.html`（新增）
- `assets/voice/pcm-processor.js`（新增，可选）

### 回滚规则
删除三个新增文件即可。

### 验证检查项
- run_frontend.py 独立端口提供 index.html。
- 前端用 AudioWorklet（非 ScriptProcessorNode）。
- 上行帧符合二步成帧协议。
- 下行 MP3 blob 用 `<audio>` 整段播放。
- 监听 voice.error/playback.failed 清队列+展示文本。
- 打断按钮发 barge_in。
- 浏览器无 console 错误。

### 禁止项
- 不得用已废弃的 ScriptProcessorNode。
- 不得分块解码 MP3（必须整段 blob 播放）。
- 不得新增控制帧（复用既有 voice.error/playback.failed）。
- 不得修改 websocket_server 协议（前端适配既有协议）。
- 不得引入前端构建工具/框架（纯原生 HTML/JS）。
- 不得连接非本机 WS 地址。

### 单次提交边界
本任务 = 前端三文件一次提交。

---

## T10 · 全量回归与交付

### 允许文件清单
- `docs/项目总控/STATUS.md`（回归与验收记录）

### 回滚规则
本任务仅记录，无代码改动，无需回滚。

### 验证检查项
- `pytest tests/unit tests/integration` 退出码 0。
- 真实语音端到端闭环可用。
- 首响延迟 ≤5s；二次对话 ≤3s（达标或记录偏差）。
- STATUS.md 含完整改动清单（新增/修改/删除）。
- 用户确认收到全部改动文件。

### 禁止项
- 不得为通过回归修改业务代码（只记录偏差）。
- 不得执行 Git 操作。
- 不得遗漏改动文件清单。

### 单次提交边界
本任务 = STATUS.md 验收记录。最终由用户一次性提交全部 T0–T10 改动（或按任务粒度分批提交，由用户决定）。

---

## 引用一致性矩阵

| 任务 | task.md（目标/优先级/依赖/验收） | spec.md（执行步骤） | harness.md（约束） |
|------|------|------|------|
| T0 | §T0 | §T0 | §T0 |
| T1 | §T1 | §T1 | §T1 |
| T2 | §T2 | §T2 | §T2 |
| T3 | §T3 | §T3 | §T3 |
| T4 | §T4 | §T4 | §T4 |
| T5 | §T5 | §T5 | §T5 |
| T6 | §T6 | §T6 | §T6 |
| T7 | §T7 | §T7 | §T7 |
| T8 | §T8 | §T8 | §T8 |
| T9 | §T9 | §T9 | §T9 |
| T10 | §T10 | §T10 | §T10 |

三文档以 T0–T10 编号贯穿：task.md 定义"做什么"，spec.md 定义"怎么做"，harness.md 定义"不能做什么"。
