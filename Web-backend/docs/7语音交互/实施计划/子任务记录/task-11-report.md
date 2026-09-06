# Task 11 实施记录：死代码清理、全量验收与状态收口

## 实施结果

- 权威语音业务链只保留 `AudioTransport -> VAD/Endpoint -> ASRProvider -> VoiceQueryNormalizer -> VoiceSessionOrchestrator -> AppPipeline -> SpokenAnswerPlanner -> TTSProvider`。
- 删除 `MockVoiceLoop`、旧 `VoiceTurnEvent`、legacy metric 单 provider/同步 record、callback finalizer 以及 BargeInController 伪 Prompt/旧同步 adapter。
- `terms` 仅用于 ASR 纠错，`simplifications` 仅用于 P6 表达改写；启动严格验证两分区，独立 digest 共同影响脱敏 snapshot。
- checkpoint 容量从 `feedback.checkpoint.max_items` 读取，以线程安全 FIFO 策略阻止无界增长，审计不包含 run/session 私密文本。

## TDD 与评审证据

1. 首轮 checkpoint/词典红测：`9 failed, 10 passed`，退出码 `1`。
2. 兼容与后台任务红测：`1 failed, 1 passed`，退出码 `1`，并复现 `Task exception was never retrieved`。
3. 配置 snapshot 红测：`2 failed`，退出码 `1`，原因为 simplifications 未进 snapshot 且非法值未在启动失败。
4. 逐项根因修复后，第三轮独立评审 PASS：Critical `0`、Important `0`、Minor `0`。

## 最终验收

- Python `3.13.13`；解释器 `D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe`；`websockets 15.0.1`。
- compileall 退出码 `0`；`git diff --check` 退出码 `0`。
- unit voice：`254 passed`，0 failed，0 skipped，退出码 `0`。
- integration voice/app/feedback：`79 passed`，0 failed，0 skipped，退出码 `0`。
- voice WebSocket/flow E2E：`21 passed`，0 failed，0 skipped，退出码 `0`。
- 全量 pytest：`552 passed`，0 failed，0 skipped，退出码 `0`。
- deployment：`status=ok`、`failed_checks=[]`，退出码 `0`；Mock offline，`real_provider_pending=true`，raw audio persistence=`false`。
- smoke：`3/3`、`pass_rate=1.0`，退出码 `0`；TTS chunks `3`、close `1000`、port released=`true`、pending task count=`0`。
- trace export 退出码 `0`，产物为 `docs/评测与验收/追踪报告/voice_realtime_refactor_acceptance.md`；固定隐私扫描零匹配。

## 零引用与边界

- `MockVoiceLoop`、`from voice.voice_loop`、`VoiceTurnEvent`、精确 legacy voice YAML 键、`DEFAULT_TERM_CORRECTIONS`、callback finalizer 与 barge-in 伪 Prompt 零匹配。
- 宽泛 `low_confidence_threshold` 扫描的命中只是正确新键 `asr_low_confidence_threshold` 与 P2 memory 合法键，不能误删。
- `MockVADService` 只作为统一 Protocol/registry 的有效 Mock 和单元 fixture；无 `MockAudioTransport.send`。
- 真实 ASR、真实 TTS、真实模型、生产鉴权/TLS/限流、媒体服务与生产部署仍属后续范围。

