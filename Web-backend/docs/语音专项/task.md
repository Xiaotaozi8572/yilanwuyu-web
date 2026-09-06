# 真实语音交互专项 · 任务分解（task.md）

> 来源：`tmp/voice_real_plan_final.md`（v2 最终版）拆解
> 配套文档：`spec.md`（执行步骤）、`harness.md`（约束规范）
> 编号约定：T0–T10，三文档共用同一编号体系以保持引用一致。
> 原则：不执行任何 Git 操作；AI 不自行修改安全边界；每任务完成后由用户手动提交形成回滚点。

---

## 任务总览与依赖图

```
T0(授权入档,用户手动)
 └─ T1(治理文档更新)
     └─ T2(依赖+环境核验)
         └─ T3(配置扩展)
             ├─ T4(ASR Provider) ─┐
             └─ T5(TTS Provider) ─┤
                 T6(注册+接线) ←──┘
                     ├─ T7(测试与标定)
                     ├─ T8(语音入口)
                     └─ T9(前端页面)
                         T10(全量回归与交付) ← T7,T8,T9
```

| 任务 | 名称 | 优先级 | 前置依赖 | 产出物 |
|------|------|--------|---------|--------|
| T0 | 授权专节入档 | P0（阻断） | 无（用户手动） | task.md 授权专节 |
| T1 | 治理文档更新 | P1 | T0 | harness/STATUS/AUTO_DEV 更新 |
| T2 | 依赖声明与环境核验 | P1 | T1 | pyproject.toml + uv.lock |
| T3 | 配置扩展 | P1 | T2 | voice.yaml + settings.py |
| T4 | ASR Provider 实现 | P1 | T3 | whisper_asr.py |
| T5 | TTS Provider 实现 | P1 | T3 | edge_tts.py |
| T6 | Provider 注册与接线 | P1 | T4, T5 | providers.py + orchestrator.py + asr.py |
| T7 | 测试与标定 | P2 | T6 | 测试文件 + 标定报告 |
| T8 | 语音入口 | P2 | T6 | run_voice.py |
| T9 | 前端页面 | P2 | T6 | run_frontend.py + index.html |
| T10 | 全量回归与交付 | P3 | T7, T8, T9 | 回归报告 + 一次性交付包 |

---

## T0 · 授权专节入档（用户手动执行）

- **目标**：将 v2 计划第二节的"授权专节草案"由用户审阅确认后手动写入 `docs/项目总控/task.md`，建立合规前提。
- **优先级**：P0（阻断后续所有任务）
- **前置依赖**：无
- **执行者**：用户（非 AI）
- **验收标准**：
  1. `docs/项目总控/task.md` 末尾存在带日期"2026-08-13"的"真实语音 Provider 专项：用户授权"专节。
  2. 专节包含：授权范围、允许依赖（faster-whisper<2、edge-tts<7）、允许文件清单、edge-tts 三项知情决策（非官方端点/GPL-3.0/文本出境）、停止条件、回滚策略。
  3. 用户明确告知 AI 授权已完成。
- **对应约束**：harness.md §T0
- **对应步骤**：spec.md §T0

---

## T1 · 治理文档更新

- **目标**：依据已入档的授权专节，更新 harness.md（解除真实 ASR/TTS 禁令、批准依赖）、STATUS.md（记录决策）、AUTO_DEV.md（执行规则节）。
- **优先级**：P1
- **前置依赖**：T0
- **验收标准**：
  1. `harness.md` P7 专项授权边界明确允许 faster-whisper（本地推理、无密钥）与 edge-tts（无密钥公共端点），仍禁止需密钥云服务。
  2. `harness.md` 依赖白名单含 faster-whisper<2、edge-tts<7。
  3. `STATUS.md` 记录选型、授权范围、edge-tts 知情项、新增文件清单。
  4. `AUTO_DEV.md` 增加本专项执行规则节。
- **对应约束**：harness.md §T1
- **对应步骤**：spec.md §T1

---

## T2 · 依赖声明与环境核验

- **目标**：在 pyproject.toml 声明 faster-whisper、edge-tts，在指定 Python 3.11.9 核验环境执行 uv lock + uv sync 并记录解析结果。
- **优先级**：P1
- **前置依赖**：T1
- **验收标准**：
  1. `pyproject.toml` dependencies 含 `faster-whisper>=1.0.3,<2`、`edge-tts>=6.1.0,<7`。
  2. `uv.lock` 已更新且在 Python 3.11.9 环境核验通过（无解析冲突）。
  3. 核验记录写入 STATUS.md（解析结果、cp313 wheel 可用性）。
  4. 未引入授权外的依赖。
- **对应约束**：harness.md §T2
- **对应步骤**：spec.md §T2

---

## T3 · 配置扩展

- **目标**：扩展 `configs/voice.yaml`（provider 切换 whisper/edge + asr_config/tts_config + 阈值下调）与 `src/voice/settings.py`（承载新配置字段）。
- **优先级**：P1
- **前置依赖**：T2
- **验收标准**：
  1. `voice.yaml` 的 `asr_provider: whisper`、`tts_provider: edge`、`asr_low_confidence_threshold: 0.40`，含 asr_config/tts_config。
  2. `settings.py` 的 `VoiceSettings` 含 `asr_config`/`tts_config` 字段，`_VOICE_FIELDS` 与 `from_file` 解析同步更新。
  3. 未知字段仍被拒绝；既有隐私硬约束（raw_audio_persist=False 等）不变。
  4. settings 单测通过（新字段校验 + 快照断言）。
- **对应约束**：harness.md §T3
- **对应步骤**：spec.md §T3

---

## T4 · ASR Provider 实现

- **目标**：新增 `src/voice/whisper_asr.py`，实现 `WhisperASRProvider`（name="whisper"），含模型单例缓存、置信度稳健映射、initial_prompt 术语、懒加载。
- **优先级**：P1
- **前置依赖**：T3
- **验收标准**：
  1. `WhisperASRProvider` 实现 `ASRProvider` Protocol（name + transcribe）。
  2. 模型模块级单例缓存（按 model_size/device/compute_type 做 key），跨 turn 复用，推理用锁串行化。
  3. 构造函数接收 `settings` kwarg；懒加载（import voice 不触发下载）。
  4. PCM(int16)→float32 转换正确；置信度映射含 no_speech_prob 处理。
  5. 纯逻辑单测（注入 stub 模型）通过：PCM 转换、异常路径、协议合规。
- **对应约束**：harness.md §T4
- **对应步骤**：spec.md §T4

---

## T5 · TTS Provider 实现

- **目标**：新增 `src/voice/edge_tts.py`，实现 `EdgeTTSProvider`（name="edge"），一 segment 一完整 MP3 chunk，复用 PlaybackHandle，不重写降级。
- **优先级**：P1
- **前置依赖**：T3
- **验收标准**：
  1. `EdgeTTSProvider` 实现 `TTSProvider` Protocol（name + synthesize_stream）。
  2. 构造函数与 `MockTTSProvider` 对称（接收 settings + pronunciation_lexicon）。
  3. 一个 spoken segment 合成为一个完整 MP3 blob 作为单个 TTSChunk.payload。
  4. 复用 PlaybackHandle 的分段/时长估算/取消/ack 时序，不重写。
  5. mock edge-tts 的单测通过：chunk 非空、一 segment 一 chunk、取消生效、失败置 FAILED。
- **对应约束**：harness.md §T5
- **对应步骤**：spec.md §T5

---

## T6 · Provider 注册与接线

- **目标**：`providers.py` 重命名 `with_mock_defaults`→`with_default_providers` 并注册 whisper/edge；`orchestrator.py` ASR 创建传 settings；`asr.py` MockASRProvider 加 settings 参数；全量更新调用方。
- **优先级**：P1
- **前置依赖**：T4, T5
- **验收标准**：
  1. `providers.py` 的 `with_default_providers()` 注册 mock+whisper+edge，无旧方法别名。
  2. `orchestrator.py:1149` ASR 创建传 `settings=self.settings`。
  3. `MockASRProvider.__init__` 增加 `settings=None` 可选参数。
  4. 全部调用方更新：orchestrator.py:134、settings.py:178、scripts/validate_deployment.py:152、tests/unit/voice/test_provider_registry.py。
  5. **不新增任何 TTS 失败降级代码**（既有已实现，T7 验收）。
  6. 既有 voice 测试回归通过。
- **对应约束**：harness.md §T6
- **对应步骤**：spec.md §T6

---

## T7 · 测试与标定

- **目标**：新增 whisper/edge provider 测试；置信度阈值真实样本标定；真实语音验收标准；TTS 失败降级链路验收。
- **优先级**：P2
- **前置依赖**：T6
- **验收标准**：
  1. `test_whisper_asr.py`（纯逻辑单测 + integration）、`test_edge_tts.py`（mock + integration）存在且通过。
  2. pyproject.toml 注册 `integration` marker。
  3. 置信度标定：20 句航空问题录音，≥80% 不触发澄清；标定结果写入 STATUS.md。
  4. TTS 断网集成测试：answer.display 已发、voice.error/playback.failed 已发、状态机转 CLARIFY、degradation_action 记录。
  5. VAD 阈值人工标定（vad_threshold/endpoint_silence_ms）行为可接受。
- **对应约束**：harness.md §T7
- **对应步骤**：spec.md §T7

---

## T8 · 语音入口

- **目标**：新增 `scripts/run_voice.py`，启动 WebSocket voice server（复用 serve_voice），绑定 127.0.0.1:随机端口。
- **优先级**：P2
- **前置依赖**：T6
- **验收标准**：
  1. `scripts/run_voice.py` 可独立运行，启动 WS server 并打印访问端口。
  2. 复用 `voice.websocket_server.serve_voice`，不重复实现 WS 逻辑。
  3. 仅 loopback（127.0.0.1），符合 harness"本地随机端口"。
- **对应约束**：harness.md §T8
- **对应步骤**：spec.md §T8

---

## T9 · 前端页面

- **目标**：新增 `scripts/run_frontend.py`（独立 HTTP 静态服务）+ `assets/voice/index.html`（AudioWorklet 采集 + MP3 整段播放 + 失败处理 + 打断）。
- **优先级**：P2
- **前置依赖**：T6
- **验收标准**：
  1. `run_frontend.py` 独立端口提供 index.html，与 WS 端口分离。
  2. 前端用 AudioWorklet（非废弃的 ScriptProcessorNode）采集 16kHz/mono/16-bit PCM。
  3. 上行帧符合 websocket_server 二步成帧协议（JSON头 + binary PCM）。
  4. 下行 MP3 blob 用 `<audio>` 整段播放，播完发 ack。
  5. 监听 voice.error/playback.failed：清空播放队列 + 展示 answer.display 文本。
  6. 打断按钮发 barge_in 事件。
- **对应约束**：harness.md §T9
- **对应步骤**：spec.md §T9

---

## T10 · 全量回归与交付

- **目标**：跑全量 pytest 回归 + 真实语音端到端验收，汇总改动，一次性交付给用户手动提交。
- **优先级**：P3
- **前置依赖**：T7, T8, T9
- **验收标准**：
  1. `pytest tests/unit tests/integration` 全绿（含 voice 回归）。
  2. 真实语音端到端：麦克风说话→识别→RAG回答→TTS播放闭环可用。
  3. 首响延迟 ≤5s（small/int8 CPU）；二次对话 ≤3s（模型已缓存）。
  4. 改动清单汇总（新增/修改/删除文件）写入 STATUS.md。
  5. 全部改动一次性交付，用户手动 Git 提交（AI 不执行 Git）。
- **对应约束**：harness.md §T10
- **对应步骤**：spec.md §T10
