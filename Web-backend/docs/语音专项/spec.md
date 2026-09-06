# 真实语音交互专项 · 执行步骤（spec.md）

> 来源：`tmp/voice_real_plan_final.md`（v2 最终版）拆解
> 配套文档：`task.md`（任务分解）、`harness.md`（约束规范）
> 编号约定：T0–T10，与 task.md 一一对应。每个任务的"改动文件""改动范围""预期行为""验证方式"逐项列出。
> 原则：步骤具体可执行，避免模糊指令；每步标注文件绝对路径或相对项目根路径。

---

## T0 · 授权专节入档（用户手动）

### 步骤
1. 用户审阅 `tmp/voice_real_plan_final.md` 第二节"授权专节草案"。
2. 确认三项知情决策：
   - edge-tts 非官方逆向端点（非微软官方 API）
   - edge-tts GPL-3.0 许可（打包分发注意传染性）
   - TTS 合成文本出境（发往第三方端点）
3. 用户将专节内容手动追加到 `docs/项目总控/task.md` 末尾。
4. 用户告知 AI 授权已完成。

### 改动文件
- `docs/项目总控/task.md`（用户手动追加）

### 预期行为
task.md 末尾出现带"2026-08-13"日期的"真实语音 Provider 专项：用户授权"专节，含范围/依赖/文件清单/知情项/停止条件/回滚。

### 验证方式
- 检索 task.md 含"真实语音 Provider 专项：用户授权（2026-08-13）"标题。
- 专节含 faster-whisper<2、edge-tts<7 依赖声明。
- 专节含 scripts/run_voice.py、scripts/run_frontend.py、assets/voice/index.html 文件清单。

---

## T1 · 治理文档更新

### 步骤
1. **`docs/项目总控/harness.md`** — P7 专项授权边界段：
   - 将"严禁真实 ASR/TTS"修改为"允许 faster-whisper（本地推理，无密钥）与 edge-tts（无密钥公共 TTS 端点）；仍严禁需密钥的云 ASR/TTS、生产数据库、真实模型服务"。
   - 依赖白名单段追加："P7 真实 provider 推进阶段另允许 faster-whisper<2、edge-tts<7"。
   - 追加不变量（法典化现状）："edge-tts 失败降级文本回答已由现有 orchestrator/websocket_server 代码成立。"
2. **`docs/项目总控/STATUS.md`** — 追加决策记录：选型、授权范围、edge-tts 三项知情、新增文件清单、置信度阈值下调说明。
3. **`docs/项目总控/AUTO_DEV.md`** — 参照 LangGraph 先例增加本专项执行规则节（范围、停止条件、回滚）。

### 改动文件
- `docs/项目总控/harness.md`
- `docs/项目总控/STATUS.md`
- `docs/项目总控/AUTO_DEV.md`

### 预期行为
harness 不再禁止 faster-whisper/edge-tts；STATUS 记录决策；AUTO_DEV 含执行规则。

### 验证方式
- harness.md 检索到 "faster-whisper" 且无"严禁真实 ASR/TTS"针对本专项的表述。
- STATUS.md 含 "2026-08-13 真实语音 Provider 推进"记录。
- AUTO_DEV.md 含本专项执行规则节。

---

## T2 · 依赖声明与环境核验

### 步骤
1. **`pyproject.toml`** — `[project].dependencies` 列表追加两行：
   ```toml
   "faster-whisper>=1.0.3,<2",
   "edge-tts>=6.1.0,<7",
   ```
2. 在指定 Python 3.11.9 核验环境（`D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe`）执行：
   - `uv lock` 更新 `uv.lock`
   - `uv sync` 验证可安装
3. 核验 ctranslate2/onnxruntime 在 Python 3.13 下 cp313 wheel 可用性。
4. 将核验结果（解析版本、wheel 可用性）写入 `docs/项目总控/STATUS.md`。

### 改动文件
- `pyproject.toml`
- `uv.lock`
- `docs/项目总控/STATUS.md`（核验记录）

### 改动范围
仅 dependencies 列表新增两行；不修改既有依赖版本；不引入授权外依赖。

### 预期行为
3.11.9 环境 uv sync 成功；3.13 环境 cp313 wheel 可用或记录为待确认。

### 验证方式
- `uv sync` 无报错。
- `python -c "import faster_whisper; import edge_tts"` 成功。
- STATUS.md 含核验记录。

---

## T3 · 配置扩展

### 步骤
1. **`configs/voice.yaml`** — 修改以下字段：
   - `asr_provider: mock` → `asr_provider: whisper`
   - `asr_low_confidence_threshold: 0.65` → `0.40`
   - `tts_provider: mock` → `tts_provider: edge`
   - 新增 `asr_config` 段：model_size=small, device=cpu, compute_type=int8, language=zh, initial_prompt="航空、飞机、发动机、APU、副翼、襟翼、起落架、尾翼、机翼"
   - 新增 `tts_config` 段：voice=zh-CN-XiaoxiaoNeural, rate="+0%", volume="+0%"
2. **`src/voice/settings.py`** — 扩展承载新配置：
   - `_VOICE_FIELDS` frozenset 追加 `"asr_config"`、`"tts_config"`。
   - `VoiceSettings` dataclass 新增字段：
     ```python
     asr_config: Mapping[str, Any] = field(default_factory=dict)
     tts_config: Mapping[str, str] = field(default_factory=dict)
     ```
   - `from_file`：asr_config/tts_config 为可选 mapping，缺省空 dict；校验 value 为 str/int，拒绝嵌套 dict/list。
   - `_snapshot_payload` 包含新字段。
   - `_normalize_known_providers` 默认来源改为 `with_default_providers()`（注：该方法在 T6 实现，本步先保留 `with_mock_defaults`，T6 同步切换；或在 T3 即引用并在 T6 落地——以 T6 落地为准，本步 settings 仅扩展字段）。
3. 既有隐私硬约束（raw_audio_persist_enabled=False、raw_transcript_logging_enabled=False、retention=0、redaction_mode=full）保持不变。

### 改动文件
- `configs/voice.yaml`
- `src/voice/settings.py`

### 改动范围
仅新增 asr_config/tts_config 字段与解析；不改动既有字段语义；不引入凭据字段。

### 预期行为
voice.yaml 可被 VoiceSettings.from_file 正确解析；未知字段仍被拒；asr_config/tts_config 可通过 settings 访问。

### 验证方式
- `python -c "from voice.settings import VoiceSettings; s=VoiceSettings.from_file('configs/voice.yaml'); print(s.asr_provider, s.tts_provider, s.asr_config)"` 输出 whisper edge {...}。
- settings 单测：新增 asr_config/tts_config 校验断言 + snapshot 含新字段。
- 既有 `tests/unit/voice/` settings 测试通过。

---

## T4 · ASR Provider 实现

### 步骤
1. **新增 `src/voice/whisper_asr.py`**：
   - 类 `WhisperASRProvider`，类属性 `name = "whisper"`。
   - `__init__(self, *, settings: VoiceSettings | None = None)`：从 settings.asr_config 读取 model_size/device/compute_type/language/initial_prompt，存为实例属性；不加载模型。
   - 模块级缓存：`_MODEL_CACHE: dict[tuple, object]`，`_MODEL_LOCK = asyncio.Lock()`。
   - `_get_model(self) -> WhisperModel`：按 (model_size, device, compute_type) 查缓存，未命中则 `from faster_whisper import WhisperModel` 实例化并存缓存。
   - `transcribe(self, frames: AsyncIterator[AudioFrame]) -> AsyncIterator[ASRResult]`：
     - 累积 frames 的 payload bytes 到 buffer。
     - 流结束后 `asyncio.to_thread(self._transcribe_sync, buffer)`。
     - `_transcribe_sync`：`audio = np.frombuffer(buffer, dtype=np.int16).astype(np.float32)/32768.0`；`segments, info = model.transcribe(audio, language=..., initial_prompt=...)`；每个 segment 转 ASRResult。
   - 置信度映射：`confidence = max(0.0, min(1.0, exp(avg_logprob)*1.5))`；若 `no_speech_prob > 0.8` 则 confidence=0。
   - 异常：模型加载/推理失败抛 `VoiceASRError`（from voice.errors）。
2. **新增 `tests/unit/voice/test_whisper_asr.py`**：
   - 纯逻辑单测：注入 stub 模型（fake transcribe 返回固定 segments），验证 PCM→float32、ASRResult 字段、confidence 映射、空帧/损坏 PCM 异常、protocol 合规。
   - 集成测试 `@pytest.mark.integration`：用 fixture WAV 转 PCM，真实模型转写。

### 改动文件
- `src/voice/whisper_asr.py`（新增）
- `tests/unit/voice/test_whisper_asr.py`（新增）

### 改动范围
仅新增文件；不改 contracts/asr.py/orchestrator.py（orchestrator 接线在 T6）。

### 预期行为
WhisperASRProvider 可被 ProviderRegistry.create_asr("whisper", settings=...) 创建；transcribe 产出 ASRResult 流。

### 验证方式
- 纯逻辑单测全绿。
- `python -c "from voice.whisper_asr import WhisperASRProvider; p=WhisperASRProvider(); print(p.name)"` 输出 whisper（不触发模型下载）。

---

## T5 · TTS Provider 实现

### 步骤
1. **新增 `src/voice/edge_tts.py`**：
   - 类 `EdgeTTSProvider`，类属性 `name = "edge"`。
   - `__init__(self, *, settings: VoiceSettings | None = None, pronunciation_lexicon: Mapping[str, str] | None = None)`：与 MockTTSProvider 对称；从 settings.tts_config 读 voice/rate/volume；复用 load_string_mapping 加载发音词典。
   - `synthesize_stream(self, answer: SpokenAnswer, config: Mapping[str, Any]) -> PlaybackHandle`：
     - 校验 answer provenance（与 MockTTSProvider 一致：source_evidence_package_id 必填）。
     - 校验 config（max_seconds/chunk_delay_seconds，与 MockTTSProvider 一致）。
     - 拼接 display_segments（answer_brief + spoken_steps + follow_up_prompt），应用发音词典替换。
     - 时长预算校验（estimate_spoken_seconds）。
     - 返回 `PlaybackHandle(provider="edge", segments=..., encoder=self._encode_segment, ...)`。
   - `_encode_segment(self, segment, sequence_no, cancel_event) -> bytes | None`：
     - `await asyncio.sleep(0)`；cancel 则返回 None。
     - `communicate = edge_tts.Communicate(segment, self._voice, rate=self._rate, volume=self._volume)`。
     - `async for chunk in communicate.stream(): if chunk["type"]=="audio": mp3_parts.append(chunk["data"])`。
     - 返回 `b"".join(mp3_parts)`（完整 MP3 blob）。
     - 异常抛出（由 PlaybackHandle 捕获置 FAILED）。
2. **新增 `tests/unit/voice/test_edge_tts.py`**：
   - mock edge_tts.Communicate.stream（返回固定 MP3 bytes），验证 synthesize_stream 返回 PlaybackHandle、chunk payload 非空、一 segment 一 chunk、取消生效。
   - 网络失败测试：mock stream 抛异常，验证 PlaybackHandle 置 FAILED。
   - 集成测试 `@pytest.mark.integration`：真实 edge-tts 合成。

### 改动文件
- `src/voice/edge_tts.py`（新增）
- `tests/unit/voice/test_edge_tts.py`（新增）

### 改动范围
仅新增文件；不改 tts.py/contracts/orchestrator；不重写 PlaybackHandle；不新增降级代码。

### 预期行为
EdgeTTSProvider 可被 create_tts("edge", settings=...) 创建；synthesize_stream 返回 PlaybackHandle，每 segment 一个完整 MP3 chunk。

### 验证方式
- mock 单测全绿。
- `python -c "from voice.edge_tts import EdgeTTSProvider; p=EdgeTTSProvider(); print(p.name)"` 输出 edge。

---

## T6 · Provider 注册与接线

### 步骤
1. **`src/voice/providers.py`**：
   - `with_mock_defaults`（88-100行）重命名为 `with_default_providers`。
   - 注册体追加：
     ```python
     from voice.whisper_asr import WhisperASRProvider
     from voice.edge_tts import EdgeTTSProvider
     registry.register_asr("whisper", WhisperASRProvider)
     registry.register_tts("edge", EdgeTTSProvider)
     ```
   - 保留 mock 注册。删除旧 `with_mock_defaults`（不留别名）。
2. **`src/voice/asr.py`** — `MockASRProvider.__init__` 增加 `settings: VoiceSettings | None = None` 可选参数（忽略即可），保持与真实 provider 构造签名统一。
3. **`src/voice/orchestrator.py`**：
   - 第134行：`ProviderRegistry.with_mock_defaults()` → `with_default_providers()`。
   - 第1149行：`self.registry.create_asr(self.settings.asr_provider)` → `create_asr(self.settings.asr_provider, settings=self.settings)`。
4. **`src/voice/settings.py`** — 第178行：`ProviderRegistry.with_mock_defaults()` → `with_default_providers()`。
5. **`scripts/validate_deployment.py`** — 第152行：同步更新调用。
6. **`tests/unit/voice/test_provider_registry.py`** — 第33/48行：更新断言（含 whisper/edge）。
7. **全量 grep `with_mock_defaults`** 确保无遗漏。
8. **不新增任何 TTS 失败降级代码**（既有 orchestrator.py:192-231 + websocket_server.py:518-528/535-541/577-586 已实现，T7 验收）。

### 改动文件
- `src/voice/providers.py`
- `src/voice/asr.py`
- `src/voice/orchestrator.py`
- `src/voice/settings.py`
- `scripts/validate_deployment.py`
- `tests/unit/voice/test_provider_registry.py`

### 改动范围
仅注册重命名+接线+调用方同步；不改 PlaybackHandle/contracts/transport；不重写降级。

### 预期行为
with_default_providers 注册 mock+whisper+edge；orchestrator 用新 registry；ASR 创建传 settings。

### 验证方式
- `grep -rn with_mock_defaults src tests scripts` 无结果。
- `pytest tests/unit/voice tests/integration/voice_loop` 全绿。
- `python -c "from voice.providers import ProviderRegistry; r=ProviderRegistry.with_default_providers(); print(sorted(r.known_providers['asr']), sorted(r.known_providers['tts']))"` 含 whisper/edge。

---

## T7 · 测试与标定

### 步骤
1. **`pyproject.toml`** — `[tool.pytest.ini_options]` 注册 marker：
   ```toml
   markers = ["integration: requires network or model download"]
   ```
2. **置信度标定**：
   - 准备 20 句标准普通话航空问题录音（WAV 16kHz mono 16-bit），含 APU/副翼/襟翼/起落架等术语。
   - 用 WhisperASRProvider 转写，记录每句 avg_logprob/no_speech_prob/映射 confidence/是否过 0.40 门禁。
   - 标定 asr_low_confidence_threshold 使 ≥80%（≥16句）通过。
   - 标定结果写入 `docs/项目总控/STATUS.md`。
3. **TTS 失败降级链路验收测试** — 新增 `tests/integration/voice_loop/test_tts_failure_degradation.py`：
   - mock EdgeTTSProvider._encode_segment 抛异常。
   - 验证 orchestrator 状态机转 CLARIFY、degradation_action="display_answer_only" 记录、websocket_server 发 answer.display + voice.error + playback.failed。
4. **VAD 阈值人工标定**：真实麦克风说话/静音切换，记录 vad_threshold/endpoint_silence_ms 是否合适，写入 STATUS.md。
5. **真实语音验收**：记录首响延迟、二次对话延迟、端点检测可靠性，对照 task.md T10 验收标准。

### 改动文件
- `pyproject.toml`（marker 注册）
- `tests/integration/voice_loop/test_tts_failure_degradation.py`（新增）
- `docs/项目总控/STATUS.md`（标定记录）

### 改动范围
仅测试与记录；不改业务代码。

### 预期行为
标定完成；降级链路测试通过；验收指标达标或记录偏差。

### 验证方式
- `pytest tests/unit/voice/test_whisper_asr.py tests/unit/voice/test_edge_tts.py` 全绿。
- `pytest tests/integration/voice_loop/test_tts_failure_degradation.py` 全绿。
- `pytest -m integration`（手动，需模型/网络）。
- STATUS.md 含标定数据。

---

## T8 · 语音入口

### 步骤
1. **新增 `scripts/run_voice.py`**：
   - `import asyncio`、`from voice.websocket_server import serve_voice`、`from voice.settings import VoiceSettings`。
   - 读取 `configs/voice.yaml` 构造 settings。
   - 调用 `serve_voice(host="127.0.0.1", port=0, settings=settings)`（port=0 让 OS 分配随机端口）。
   - 打印实际绑定端口供前端连接。
   - 主循环 `asyncio.run`。
2. 不重复实现 WS 逻辑，仅包装 serve_voice。

### 改动文件
- `scripts/run_voice.py`（新增）

### 改动范围
仅新增启动脚本；不改 websocket_server.py 业务逻辑（若 serve_voice 签名需微调以接受外部 settings，最小化调整并记录）。

### 预期行为
`python scripts/run_voice.py` 启动 WS server，打印端口，阻塞运行。

### 验证方式
- 脚本可运行并打印端口。
- WS 端口可被前端连接（T9 验证）。

---

## T9 · 前端页面

### 步骤
1. **新增 `scripts/run_frontend.py`**：
   - 用 `http.server` 在独立端口（如 8000）提供 `assets/voice/` 静态文件。
   - 打印访问 URL（如 http://127.0.0.1:8000/index.html）。
2. **新增 `assets/voice/index.html`**：
   - **采集**：`getUserMedia({audio:{sampleRate:16000,channelCount:1}})` → AudioContext(16000) → AudioWorkletNode（加载 worklet processor 重采样+切 20ms 帧+转 Int16Array）。
   - **上行**：每帧 WebSocket.send(JSON头{type:"audio.frame",sequence,sample_rate:16000,channels:1,timestamp_ms,energy}) + WebSocket.send(pcm ArrayBuffer)。
   - **下行播放**：onmessage 收 binary（MP3 blob）→ `new Blob([data],{type:"audio/mpeg"})` → `audio.src=URL.createObjectURL(blob)` → `audio.play()` → ended 后发 ack JSON。
   - **失败处理**：onmessage 收 JSON {type:"voice.error"} 或 {type:"playback.failed"} → 清空播放队列 → 显示 answer.display 文本气泡"网络波动，语音播报中断，请阅读以下文字解答：..."。
   - **打断**：按钮 onclick → WebSocket.send(JSON{type:"barge_in"})。
   - **状态显示**：IDLE/LISTENING/TRANSCRIBING/SPEAKING 等状态实时显示。
3. **可选 worklet 文件** `assets/voice/pcm-processor.js`：AudioWorkletProcessor 实现 16kHz/mono/16-bit 帧切分。

### 改动文件
- `scripts/run_frontend.py`（新增）
- `assets/voice/index.html`（新增）
- `assets/voice/pcm-processor.js`（新增，可选）

### 改动范围
仅前端与静态服务；不改 websocket_server 协议（前端适配既有二步成帧）。

### 预期行为
浏览器访问 http://127.0.0.1:8000/index.html → 授权麦克风 → 说话 → 收到语音回答播放；断网显示文本；打断可停止。

### 验证方式
- 浏览器无 console 错误。
- 说话后能收到 MP3 并播放。
- 手动断网（关闭 TTS）后前端显示文本气泡。
- 打断按钮可停止播放。

---

## T10 · 全量回归与交付

### 步骤
1. 执行全量回归：
   - `pytest tests/unit tests/integration -q`
   - `pytest tests/unit/voice tests/integration/voice_loop -q`
2. 真实语音端到端验收（T8+T9 启动后手动）：
   - 麦克风说"飞机的发动机是做什么的"→识别→RAG→TTS播放。
   - 记录首响延迟、二次对话延迟。
3. 汇总改动清单（新增/修改/删除文件）写入 `docs/项目总控/STATUS.md`。
4. 一次性交付全部改动给用户，由用户手动 Git 提交。

### 改动文件
- `docs/项目总控/STATUS.md`（回归与验收记录）

### 改动范围
仅记录；不改代码。

### 预期行为
全量测试绿；端到端可用；改动清单完整。

### 验证方式
- pytest 退出码 0。
- 端到端闭环可用。
- STATUS.md 含完整改动清单 + 验收数据。
- 用户确认收到全部改动文件。
