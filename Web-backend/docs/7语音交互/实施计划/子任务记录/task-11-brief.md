# Task 11 实施简报：死代码清理与最终验收

## 目标

在 T0–T10 调用方全部迁移后，删除 Mock-only 主链、重复契约、过期兼容和伪执行路径，并用分层测试、部署、smoke、trace、隐私与零引用扫描完成 P7 最终验收。

## 实施范围

- 删除 `src/voice/voice_loop.py`、`VoiceTurnEvent`、legacy metric `provider/record()` 与 callback finalizer 路径。
- 将已迁移的集成测试重命名为 `test_orchestrated_voice_pipeline.py`，保留 PCM frame、final gate、可信 RAG 与 TTS 公共行为断言。
- 将 ASR `terms` 和 P6 `simplifications` 置于同一词典的严格独立分区，两者分别进入配置 digest/snapshot。
- 为文本/voice checkpoint 建立配置驱动的 FIFO 有界存储与脱敏淘汰审计，保持原有 binding/delete 契约。
- 删除 BargeInController 旧同步 adapter 与未执行的 Prompt 元数据伪路径；保留真实 `cancel -> parse` 公共链。

## 验收边界

- 仅使用 Python 3.13、Mock Provider、本地随机端口和 `websockets==15.0.1`。
- 不读取真实密钥，不连接真实 ASR/TTS/模型、生产数据库或生产服务。
- 第三轮独立评审必须 Critical/Important/Minor 均为 0，所有最终命令必须提供新鲜退出码。

