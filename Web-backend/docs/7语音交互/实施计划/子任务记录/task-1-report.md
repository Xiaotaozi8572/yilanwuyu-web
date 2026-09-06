# T1 实施报告：统一语音契约与错误模型

## 任务结论

`voice.contracts` 现为后续语音组件必须迁移到的唯一目标权威入口，`core.contracts` 中的重复 `VoiceTurnEvent` 定义和注册项已删除，不存在 re-export 或 import shim。历史 transport/ASR/TTS/metrics 散落 dataclass 仍按 T3/T4/T6/T8 迁移，本报告不将它们误述为已清理。

## 失败优先证据

- 首次运行 `python -m pytest tests/unit/voice/test_voice_contracts.py -q`。
- 结果：收集失败，退出码 `2`。
- 根因：`ModuleNotFoundError: No module named 'voice.contracts'`，与目标缺陷直接对应。

## 实现摘要

- 核心契约：`AudioFrame`、`ASRResult`、`VoiceQueryObject`、`VoiceTurnEvent`、`SpokenAnswer`、`VoiceAnswerObject`、`PlaybackHandleState`、`BargeInEvent`、`VoiceMetricRecord`。
- 辅助契约：`VoiceInputEvent`、`PlaybackSnapshot`、`TTSChunk`、`TTSResult`、`VADDecision`、`EndpointDecision`、`PreRetrievalResult`。
- 错误层：`VoiceError`、`VoiceContractError`，以及配置、Provider、transport、ASR、endpoint、TTS、state、protocol、cancellation 和 processing 错误子类。
- 隐私边界：错误 details 不接受 raw audio、二进制 payload、完整 prompt、完整 transcript 或密钥类内容。

## 评审加固

- `VoiceAnswerObject.answer` 强类型为 `AnswerEnvelope`，字典边界通过 `AnswerEnvelope.from_dict` 恢复，其他类型被拒绝，往返后仍为 typed answer。
- `VoiceError.details` 在构造时递归验证和冻结，拒绝字节、敏感键、非有限数、非字符串键和不支持对象；`to_dict` 每次生成防御性纯 JSON 副本。
- `VoiceTurnEvent` 复用已有 `VoiceState` 枚举而不建立第二套业务状态，严格限定 voice source；barge-in 强制取消先于解析。
- `SpokenAnswer` 严格拒绝任何新 claim ID；冻结契约的嵌套数据防御性冻结，指标契约补齐 T8 所需可选质量字段与不含配置值的快照 ID。
- 上述评审测试首次运行为 `14 failed`、退出码 `1`；修复后聚合测试 `49 passed`，语音回归 `55 passed`，退出码均为 `0`。

### 第二轮评审加固

- `VoiceAnswerObject` 现在强制 `spoken_answer.source_answer_id == answer.answer_id`，并且容器 `source_evidence_package_id` 必须与 spoken answer 一致。
- 字典 answer 在交给 `AnswerEnvelope.from_dict` 之前先根据 dataclass 字段集严格拒绝未知嵌套字段，不再静默丢弃。
- 指标契约已删除任意 `config_snapshot` mapping，只允许最长 128 字符、不携带空白/等号/秘密值的不透明 `config_snapshot_id`。
- error details 键在敏感比较前执行 casefold，并将连字符/空白归一为下划线，嵌套的 `secret-audio`、`api-key`、`full-prompt` 和 `prompt-messages` 及大小写/空格变体均被拒绝。
- 第二轮主红测为 `8 failed`，快照 ID 格式补充红测为 `1 failed`，退出码均为 `1`；最终聚合测试 `57 passed`，语音回归 `63 passed`，compileall/diff-check 退出码为 `0`。

## 验证结果

- 契约、core 和编码聚合测试：`31 passed`，退出码 `0`。
- 相关语音单元、集成和 E2E 回归：`37 passed`，退出码 `0`。
- `compileall -q src/voice src/core`：退出码 `0`。
- 编码扫描确认新增中文文本均为有效 UTF-8，未出现 mojibake。
- 旧 `core.contracts.VoiceTurnEvent` 引用为零；`VoiceTurnEvent` 只在 `voice.contracts` 定义一次。
- 评审修复最终证据：契约/core/编码 `49 passed`；语音相关回归 `55 passed`；compileall 和 diff-check 退出码为 `0`。
- 第二轮复验更新：契约/core/编码 `57 passed`；语音相关回归 `63 passed`；compileall 和 diff-check 退出码为 `0`。

## 范围与后续门禁

T1 没有修改 transport、ASR、normalizer、TTS、metrics、配置、Provider 或主循环。旧散落 dataclass 暂时只因历史链路存在，必须由 T3/T4/T6/T8 迁移，并在 T11 扫描清理；不允许把新契约复制回其他模块。
