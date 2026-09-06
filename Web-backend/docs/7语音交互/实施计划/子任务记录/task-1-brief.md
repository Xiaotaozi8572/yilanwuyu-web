# T1 子任务简报：统一语音契约与错误模型

工作目录：`D:\APP\Python 3.13\挑战杯-voice-realtime`

基线提交：`e5d9cea03627b881a13c76fe2e247e6b98f680d2`

## 目标

建立 `voice.contracts` 作为语音数据对象的唯一权威入口，并建立稳定、可审计的语音错误模型。删除 `core.contracts.VoiceTurnEvent` 的重复定义和注册项；不保留 re-export 或 import shim。

## 允许修改

- 新建 `src/voice/contracts.py`
- 新建 `src/voice/errors.py`
- 新建 `tests/unit/voice/test_voice_contracts.py`
- 修改 `src/core/contracts.py`
- 必要时修改 `tests/unit/core/test_contracts.py`
- 更新 `docs/项目总控/STATUS.md`
- 新建本任务报告 `task-1-report.md`

不得修改 transport、ASR、normalizer、TTS、metrics 的现有实现；它们将在后续任务迁移。不得触及配置、Provider 或主循环。

## 必须实现的公共契约

至少包含并验证：

- `AudioFrame`：`session_id`、`turn_id`、`audio_stream_id`、`sequence_no`、`payload`、`sample_rate`、`channel_count`、`client_timestamp_ms`、`energy`、`scene_state`。
- `ASRResult`：`raw_transcript`、`confidence`、`is_final`、`start_ms`、`end_ms`、`language`、`candidates`、`provider`。
- `VoiceQueryObject`：原始/纠正/标准化文本、置信度、意图/目标、飞机/部件、场景对象、澄清状态、检索优先级、预检索结果 ID、最终检索计划；保留后续 normalizer 需要的 corrections/metadata 审计字段。
- `VoiceTurnEvent`：必须携带 session/turn/source、ASR final 状态、标准化查询和状态/时间审计所需字段，字段默认值须向后安全。
- `SpokenAnswer`：brief/steps/follow-up、`source_answer_id`、`source_evidence_package_id`、`new_claim_ids`。
- `VoiceAnswerObject`：最终 answer、spoken answer、播放/降级/状态审计所需的结构化容器。
- `PlaybackHandleState`：至少能表达 pending/playing/completed/cancelling/cancelled/failed。
- `BargeInEvent`：session/turn、播放句柄、检测/取消/解析时间与输入摘要所需字段；不得存 raw audio。
- `VoiceMetricRecord`：session/turn/provider/state/latency/length/取消/错误/隐私审计字段；不得把 audio payload 纳入契约。
- 后续任务合理需要的 `TTSChunk`、`TTSResult`、`VADDecision`、`EndpointDecision`、`PreRetrievalResult`、播放快照等契约可以在本任务一并定义，但不得实现业务逻辑。

所有输入验证必须明确覆盖：空必填 ID、负 sequence/timestamp/duration、非法 sample rate/channel、energy/confidence 越界、final 时间区间倒置、payload 类型、非法枚举状态。不可变事件/帧优先使用 frozen dataclass。

## 错误模型

- 稳定基类 `VoiceError`，包含 `code`、`message`、可选 `field`、可安全序列化的 details。
- `VoiceContractError` 的稳定 code 为 `VOICE_CONTRACT_INVALID`，错误字符串必须包含字段名。
- 为后续配置、Provider、状态、协议、取消和处理失败预留清晰子类/稳定 code，但不要引入业务实现。
- 错误 details 不得接受或输出原始音频、完整 prompt、密钥。

## TDD 与验证

1. 先新增公共行为红测并运行：
   `python -m pytest tests/unit/voice/test_voice_contracts.py -q`
   初始必须因模块/行为缺失失败，记录退出码和根因。
2. 实现契约和错误后运行：
   `python -m pytest tests/unit/voice/test_voice_contracts.py tests/unit/core/test_contracts.py -q`
3. 运行：
   `python -m compileall -q src/voice src/core`
4. 运行相关语音回归，确保当前旧实现尚未因本任务无意破坏：
   `python -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q`
5. 搜索并证明 `core.contracts` 中不再定义/注册 `VoiceTurnEvent`，且 `voice.contracts` 仅有一个定义。
6. 更新 STATUS，记录红测、绿测、退出码、文件列表、harness、dead-code 结果和下一任务门禁。
7. `git diff --check`、`git status --short`，提交当前任务文件：`refactor: unify voice contracts`。
8. 更新 Git 外部 SDD ledger，生成 `task-1-report.md`。

## 完成标准

- 语音契约只有一个权威模块。
- 错误 code/field 稳定且隐私安全。
- core 文本/通用契约测试不回归。
- 旧实现可以暂时继续导入其散落 dataclass，但本任务不得新增第二套兼容层；后续 T3/T4/T6/T8 会迁移并删除旧定义。
