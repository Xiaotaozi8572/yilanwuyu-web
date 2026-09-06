# T2 实施报告：配置、词典与 Provider Registry

## 任务结论

T2 已建立唯一、严格且可审计的 `VoiceSettings` 配置入口，并建立按 transport、VAD、ASR、TTS 分类的 Provider Protocol/Registry。术语与发音规则已迁出业务代码；未知配置、未知 Provider、非法值、缺失词典和原始音频持久化会在配置加载阶段失败。Mock Provider 通过同一 registry factory 创建，尚未增加 T3 之后的流式、会话、取消或 WebSocket 业务行为。

## 失败优先证据

- 首次运行 `python -m pytest tests/unit/voice/test_voice_settings.py tests/unit/voice/test_provider_registry.py -q`。
- 结果：2 个 collection error，退出码 `1`。
- 根因：`ModuleNotFoundError: No module named 'voice.settings'` 和 `No module named 'voice.providers'`，与目标缺陷直接对应。
- 第一版实现后剩余 2 个失败，退出码 `1`：一项定位到 `init=False` 的 `snapshot_id` 在 `asdict()` 前尚未赋值；另一项定位到测试将未知根键错误期望为缺失 `voice`。实现改为显式选取快照字段，并修正测试为精确报告未知根键；未放宽配置行为。

## 实现摘要

- 新增 frozen `VoiceSettings`，严格拒绝根级/voice 级未知键、缺失键、bool 冒充 int、越界阈值、非法声道、非正参数、未知 Provider、缺失/非法词典和启用原始音频持久化。
- 相对词典路径支持项目工作目录声明和配置文件同目录 fixture；内部解析为绝对 `Path`，但安全摘要只保留文件名，不泄露绝对私人路径或词典正文。
- 配置快照使用稳定 SHA-256 `snapshot_id`；安全摘要只含 Provider、数值、开关和词典文件名。
- 新增五个公共 Protocol：`AudioTransport`、`VADService`、`EndpointDetectorProtocol`、`ASRProvider`、`TTSProvider`。
- `ProviderRegistry` 分类注册/创建 factory，拒绝重复/未知名称并将 factory 异常包装为稳定 `VoiceProviderError`；错误仅保留 provider 和异常类型。
- `with_mock_defaults()` 复用现有 Mock 类；`websocket` 和 `in_memory` 均显式映射到同一个 `MockAudioTransport(name="in_memory")` 离线实现，不构造第二条语音业务链。T3 将实现实际 transport 行为。
- 新增独立 UTF-8 terminology/pronunciation lexicon；`AviationTermCorrector(corrections={})` 保持显式空词典，不再隐式回退固定字典。
- `pyproject.toml` 的唯一运行时依赖为 `websockets==15.0.1`。

## 必要范围映射

精确扫描发现 T2 前已有三个竞争性默认值位于后续任务归属文件：`VoiceQueryNormalizer` 的 `0.65`、`MockVADService` 的 `0.2`、`MockTTSProvider.synthesize()` 的 `20`。T2 的“配置唯一来源”完成标准与窄文件清单存在交叉。经主代理明确授权，进行了最小配置接线：参数改为 `None` 时从 `VoiceSettings` 读取，显式参数仍优先；未实现 T3/T4/T6 的流式、状态机或取消行为。旧构造参数名同步迁移为 `asr_low_confidence_threshold`，旧键零残留。

## 验证结果

- 目标配置/registry 测试：`44 passed`，0 failed，0 skipped，退出码 `0`。
- 配置消费、显式覆盖、normalizer 和旧组件聚合：`49 passed`，0 failed，0 skipped，退出码 `0`。
- 语音单元、集成和 E2E 回归：`107 passed`，0 failed，0 skipped，退出码 `0`。
- 文本 AppPipeline 回归：`9 passed`，0 failed，0 skipped，退出码 `0`。
- 编码测试：`3 passed`，0 failed，0 skipped，退出码 `0`。
- `python -m compileall -q src scripts`：退出码 `0`。
- TOML 解析结果：`['websockets==15.0.1']`；环境 import 版本 `15.0.1`，退出码均为 `0`。
- 精确旧键正则（排除合法 `asr_` 前缀）：零匹配，`rg` 退出码 `1`（符合预期）。
- `DEFAULT_TERM_CORRECTIONS`：零匹配，`rg` 退出码 `1`（符合预期）。
- `= 0.65`、`= 0.2`、`max_seconds: int = 20` 业务硬编码：零匹配，`rg` 退出码 `1`（符合预期）。

## 强制代码评审修复

T2 独立评审确认初始提交虽然建立了 Protocol/Registry，但只验证 `.name`，尚不能证明 registry 返回对象真正执行公共协议；同时发现 provider 自身抛出的 `VoiceProviderError` 会原样传播、路径解析受 CWD 影响、集合型兼容 allow-list 过宽，以及快照未绑定词典内容。这些初始表述已在本报告中更正，不再把“类已注册”等同于“协议可执行”。

- 评审红测首次执行：`14 failed, 42 passed`，退出码 `1`；每项失败分别对应上述 Critical/Important 缺口。
- factory 现对所有 `Exception`（含 provider 抛出的 `VoiceProviderError`）统一生成 `provider factory failed`，只保留 provider 名和异常类型，并禁止原始 message/details 传播。
- registry 会验证返回对象的非空 `name` 和分类必需方法，字典或缺少方法的对象会在创建阶段被拒绝。
- 现有 Mock 类采用单实现双入口过渡：ASR 统一返回 `voice.contracts.ASRResult`，支持 frame async iterator 和旧无参调用；transport 支持 receive/send-audio/close；VAD 支持 frame+state 和旧 energy 调用；TTS async 入口委托现有确定性 synthesize。未实现 T3/T4/T6 的真实流式 Provider、endpoint 或取消。
- 相对词典路径只按配置目录和配置目录定义的项目根语义解析，不再优先运行时 CWD；绝对配置路径可在任意 CWD 使用。
- Provider allow-list 只接受完整的 transport/vad/asr/tts mapping，每类必须包含非空字符串名称；不再接受共享 set。
- 快照仅纳入两个词典 canonical mapping 的 SHA-256 摘要，不纳入正文或路径；词典内容变化会改变对应 digest 和整体 `snapshot_id`。
- 评审修复后目标测试：`56 passed`，0 failed，0 skipped，退出码 `0`。
- 契约回归：`54 passed`，0 failed，0 skipped，退出码 `0`。
- 语音回归：`119 passed`，0 failed，0 skipped，退出码 `0`。
- 文本 AppPipeline 回归：`9 passed`，0 failed，0 skipped，退出码 `0`。

## 状态机、取消、隐私、配置和 trace

- T2 未修改状态机、播放取消、barge-in 或 trace；这些能力由 T3 以后按固定顺序实现。
- `raw_audio_persist_enabled` 必须为 `false`，否则加载阶段失败。
- 配置和 Provider 错误不包含文件正文、绝对私人路径、factory 原始异常消息、原始音频或 Prompt。
- `VoiceSettings.safe_summary()` 不含词典正文或绝对路径，为 T8 提供不透明快照 ID。

## harness 与后续门禁

符合 `harness.md`。仅增加已批准的 `websockets==15.0.1`；未访问真实密钥、生产数据库、真实 ASR/TTS/模型或外部服务；未绕过 AppPipeline、P6 或 P2。T3 必须继续复用本 registry/settings，不得在业务层直接实例化 Provider。
