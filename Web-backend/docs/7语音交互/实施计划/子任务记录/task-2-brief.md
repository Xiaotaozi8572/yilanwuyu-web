# T2 子任务简报：配置、词典与 Provider Registry

工作目录：`D:\APP\Python 3.13\挑战杯-voice-realtime`

基线提交：`c0c59caff6b7b24f2ed90c87870865027771ed7c`

## 目标

建立唯一、严格、可审计的语音配置入口和 Provider factory/registry。把术语与发音规则移出业务代码；未知 Provider、未知键和非法参数必须在启动/加载阶段失败。不得在业务层直接选择或实例化 Mock Provider。

## 允许修改

- 新建 `src/voice/settings.py`
- 新建 `src/voice/providers.py`
- 新建 `configs/voice_terminology.yaml`
- 新建 `configs/voice_pronunciation.yaml`
- 新建 `tests/unit/voice/test_voice_settings.py`
- 新建 `tests/unit/voice/test_provider_registry.py`
- 修改 `configs/voice.yaml`
- 修改 `pyproject.toml`
- 修改 `src/voice/terminology.py`，仅用于删除硬编码词典并改为显式注入/文件加载边界；不得实现 T4 纠错流程
- 如 registry 的 Mock 默认 factory 确实需要，允许只为现有 Mock transport/VAD/ASR/TTS 类添加无行为变化的 `name = "mock"` 标识；不得提前重写 T3/T4/T6 行为
- 更新 `docs/项目总控/STATUS.md`、本任务报告和外部 SDD ledger

## 固定配置

`pyproject.toml` 的唯一新增运行时依赖：

```toml
dependencies = ["websockets==15.0.1"]
```

`configs/voice.yaml` 必须只含已声明键：

```yaml
voice:
  transport: websocket
  sample_rate: 16000
  channel_count: 1
  frame_duration_ms: 20
  vad_provider: mock
  vad_threshold: 0.2
  endpoint_silence_ms: 600
  asr_provider: mock
  asr_low_confidence_threshold: 0.65
  tts_provider: mock
  max_spoken_answer_seconds: 20
  max_spoken_answer_chars: 180
  barge_in_enabled: true
  barge_in_priority: 100
  raw_audio_persist_enabled: false
  terminology_lexicon_path: configs/voice_terminology.yaml
  pronunciation_lexicon_path: configs/voice_pronunciation.yaml
```

禁止保留旧键 `low_confidence_threshold`，禁止在 voice/input 业务代码中新增竞争性阈值默认值。注意：静态扫描必须区分合法字段名 `asr_low_confidence_threshold`，不能把其子串误报为旧键。

## VoiceSettings

- frozen typed dataclass，字段对应上述配置。
- `VoiceSettings.from_file(path, known_providers=...)` 使用现有轻量 YAML 能力，不新增 PyYAML。
- 拒绝根级/voice 级未知键、缺失键、bool 冒充 int、阈值不在 `[0,1]`、sample rate/帧长/时长/字符数/优先级非正、声道不是 1/2、原始音频默认或被配置为持久化、词典不存在。
- 配置路径解析必须明确：相对路径以项目工作目录/配置声明语义正确解析；临时测试配置可通过绝对路径或同目录 fixture 解析。
- Provider allow-list 必须按类型区分 transport/VAD/ASR/TTS，错误字段必须准确（例如未知 ASR 报 `asr_provider`）。未知 Provider 加载阶段失败。
- 提供不含敏感值的稳定 `snapshot_id`/安全摘要，供 T8 使用；不得包含词典正文、密钥或绝对私人路径。

## Provider Protocol 与 Registry

在 `voice.providers` 定义五个公共协议边界：

- `AudioTransport`
- `VADService`
- `EndpointDetectorProtocol`
- `ASRProvider`
- `TTSProvider`

异步签名应面向 T1 contracts 和后续流式实现，例如 ASR 接受 frame async iterator 并产生 partial/final `ASRResult`，TTS 返回后续可取消播放句柄边界。T2 只定义协议与 factory，不实现主循环。

`ProviderRegistry`：

- 按类别分别注册 factory，拒绝重复注册和未知名称。
- `create_transport/create_vad/create_asr/create_tts` 只能由 registry 创建。
- `with_mock_defaults()` 复用现有 Mock 类或无业务逻辑的适配构造，不创建第二套 Mock 业务链；实例 `name == "mock"`（transport 可保留 `in_memory` 名称，但配置映射必须明确）。
- factory 异常包装成稳定、隐私安全的 `VoiceProviderError`；错误 details 不含原始输入。
- 暴露每类已注册名称，供 `VoiceSettings` 启动校验使用。

## 词典

- `voice_terminology.yaml` 至少：航道比→涵道比、鸡翼→机翼、C 九一九→C919、A G 六百→AG600。
- `voice_pronunciation.yaml` 至少：C919、AG600、RAG 的播报形式。
- `terminology.py` 不得保留 `DEFAULT_TERM_CORRECTIONS` 或固定业务词典；构造器不得用 `corrections or defaults` 隐式回退。空显式词典应保持为空。
- 词典加载必须验证 mapping/string 类型和空值，错误使用 `VoiceConfigError` 且不泄露文件正文。

## TDD 与验证

1. 先写失败测试，至少覆盖未知键/Provider、缺失/范围/类型、词典路径、registry 分类/重复/未知/factory 失败、显式空词典。
2. 红测命令：
   `python -m pytest tests/unit/voice/test_voice_settings.py tests/unit/voice/test_provider_registry.py -q`
3. 实现后重跑同命令。
4. 运行契约与旧语音回归：
   `python -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q`
5. 运行编码测试与 compileall。
6. 运行精确扫描：
   - 旧键正则（排除合法 `asr_` 前缀）零命中。
   - `DEFAULT_TERM_CORRECTIONS` 零命中。
   - `= 0.65`、`= 0.2`、`max_seconds: int = 20` 不得出现在业务代码；测试数据命中需分类。
7. 检查 `websockets` 在 pyproject 仅固定一次且无其他新增 runtime dependency。
8. 更新 STATUS/report/ledger，运行 `git diff --check` 和 status，提交：`refactor: add configurable voice providers`。
9. 本任务后必须独立代码评审；未修复 Critical/Important 前不得进入 T3。

## 完成标准

- 配置与词典成为唯一参数来源。
- registry 是 Provider 创建唯一入口且可替换。
- 未知配置/Provider 早失败，错误字段准确。
- 没有硬编码术语表、旧阈值键或第二套 Mock 主流程。
- 现有文本和语音基线不回归。
