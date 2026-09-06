# T4 实施报告：流式 ASR、术语纠错与场景标准化

## 任务结论

T4 已把 ASR、术语纠错和口语查询标准化收敛到统一契约。Mock ASR 通过统一异步 iterator 在消费并校验真实 `AudioFrame` 后按序产生 partial/final；默认纠错只执行 UTF-8 配置词典；只有显式注入 `ModelClient` 时才组装并执行专用 Prompt，而且模型只能调整空白、标点、大小写等格式，不能增加或替换词法内容。`VoiceQueryNormalizer` 只返回 canonical `VoiceQueryObject`，复用 P1 `QueryUnderstandingService` 与 `SceneBinder`，不构造 `TextQueryRequest`，不调用 RAG。

## 失败优先证据

- 首次目标测试：`19 failed, 5 passed`，退出码 `1`。
- 失败根因精确对应 T4 缺口：Mock ASR 不支持事件序列；标准化器返回本地重复 dataclass；partial/final、空文本、低置信、场景指代门控不完整；无模型客户端仍组装 Prompt；模型客户端无法注入或执行。
- 首轮实现后目标测试 `24 passed`，退出码 `0`。
- 自检发现模型 Provider 抛异常仍会外泄到调用方，新增隐私回退红测后精确复现为 `1 failed`、退出码 `1`；修复后最终目标测试 `25 passed`。

## 实现摘要

- `MockASRProvider(events=...)` 保存不可破坏的事件快照；每次调用至少消费一个真实音频帧后才 yield，空帧流不伪造 final，多次调用互不消费共享状态。
- ASR 在产生结果前校验首个输入确为 canonical `AudioFrame`，并校验结果类型、provider 一致性、时间戳单调和 final 终止性；非法对象与非法序列统一抛出不含 payload repr 的隐私安全 `VoiceASRError`。
- 旧 `transcript/confidence` 构造与无参数 `transcribe()` 仅作为 T5/T10 迁移期有限兼容；Provider Registry 和新测试都走异步 iterator。
- 删除 `input.voice_query_normalizer` 的重复 `VoiceQueryObject`；新增场景候选和 uncertainty 字段到唯一 canonical contract。
- 词典默认来自 T2 `terminology_lexicon_path`，稳定覆盖“航道比/鸡翼/C 九一九/A G 六百”。无模型 client 时不创建 PromptRuntime、不 assemble Prompt。
- 显式模型路径真实调用 `assemble_bundle_for_template()` 和 `complete_structured()`；专用 schema 只允许 `corrected_transcript/confidence`，并使用保序词法签名保证输出与词典结果词法内容相同，只允许空白、Unicode 标点、大小写和兼容字符格式变化。事实扩写、实体新增、词序或词法替换均以 `model_correction_unsupported` 回退且不得进入正式检索。
- 模型异常、错误结构、空值、越界置信度和越权字段均回退词典结果；审计只保存 provider、request ID、模板 provenance、长度和稳定错误类型，不保存完整 prompt、model raw text 或私人 transcript。
- 标准化门控保留原始 ASR 置信度；模型纠错置信度单独记为 `correction_confidence`，低值使用 `model_correction_uncertain`，不会伪装为 `low_confidence_asr`。任一门控命中均保持 `final_retrieval_plan={}`。
- partial 只生成不可作为最终事实来源的预检索审计 ID；合法 final 仅输出 `eligible/source` 计划，不执行检索。
- P1 回填 intent、aircraft、component、concept/entity；可选 dialogue context 有 20 轮/每轮 500 字符硬边界，仅在当前问题含指代时读取最近非空一轮，并只提取 aircraft/component/concept。上下文能解析目标时允许无 scene object 继续，多 scene 候选仍必须澄清；metadata 只记录使用布尔值与轮数。
- 删除旧集成测试中“只要记录 Prompt 元数据即算执行”的错误断言，替换为配置词典行为、正式门控和“无 client 不得声称调用 Prompt”的公共行为断言。

## 验证证据

- 独立评审修复红测：`12 failed, 24 passed`，退出码 `1`；分别复现模型事实扩写、非 canonical 首帧、上下文越界/泄漏和双置信度混用。
- 自检继续用红测发现 scene target 会被旧 dialogue target 覆盖：首次 `1 failed`、退出码 `1`；修复为当前 scene 优先且已绑定 scene 不读取 dialogue。
- 最终目标测试：`37 passed`、0 failed、0 skipped，退出码 `0`。
- 语音、Prompt、core、旧语音集成与 E2E 相关回归：`238 passed`、0 failed、0 skipped，退出码 `0`。
- 全量 pytest：`369 passed`、0 failed、0 skipped，退出码 `0`。
- `python -m compileall -q src scripts`：退出码 `0`。
- `git diff --check`：退出码 `0`。
- `ASRResult` 与 `VoiceQueryObject` 类定义各精确命中一次，均位于 `voice.contracts`。
- normalizer/terminology 无 retrieval、generator、memory、AppPipeline、TextQueryRequest 业务导入。
- `terminology.py` 的 Prompt assemble 只有真实模型执行路径一处；无 client 测试证明不会调用。
- full pytest 产生的非确定性 smoke/trace 报告变更已恢复到 HEAD，未纳入 T4。

## 自检结论

- partial/final 隔离、场景澄清、配置词典和模型回退均在 T4 输出边界生效。
- 未访问真实 ASR、真实模型、密钥、生产数据库或外部服务；模型测试只使用本地 Mock/抛错 fixture。
- 未构造正式文本请求、证据包或最终事实来源；T5 才能消费合法 final plan。
- T4 不实现 session orchestrator、TTS、barge-in、trace exporter 或 WebSocket server，未提前进入后续任务。
