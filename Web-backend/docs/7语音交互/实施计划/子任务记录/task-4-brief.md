# T4 子任务简报：流式 ASR、术语纠错与场景标准化

工作目录：`D:\APP\Python 3.13\挑战杯-voice-realtime`

基线提交：`9aea52d1b8ded7bbbd9a1a2d0d5fd749d1dc71f2`

## 目标

让统一 ASR Provider 从连续音频帧产生 partial/final 事件，并把每个事件转换为保守、完整、可审计的 canonical `VoiceQueryObject`。partial、低置信、空文本和场景指代不明只能成为澄清/预检索输入，绝不能变成正式查询。

## 允许修改

- 修改 `src/voice/asr.py`
- 修改 `src/voice/terminology.py`
- 修改 `src/input/voice_query_normalizer.py`
- 必要时修改 `src/voice/contracts.py`，仅补齐标准化/场景候选/不确定性字段
- 修改 `tests/unit/voice/test_query_normalizer.py`
- 新建 `tests/unit/voice/test_streaming_asr.py`
- 必要时修改旧 MockVoiceLoop 集成测试中已确认错误的“只记录 Prompt 元数据”断言，必须以更强的真实纠错/partial/final 公共行为测试替代；不得迁移主循环
- 更新 STATUS、task-4-report、外部 ledger

## 流式 Mock ASR

- `MockASRProvider(events: Sequence[ASRResult])` 按顺序 async yield partial/final，至少消费一个真实 `AudioFrame` 后才产生事件。
- 保持有限的旧构造兼容（transcript/confidence）仅供 T5/T10 迁移前旧调用方运行；新 registry/测试必须走 async iterator 统一协议。
- 所有结果使用 `voice.contracts.ASRResult`，`voice.asr` 不得重新定义/别名第二个 dataclass。
- 验证事件 provider/name、partial→final 顺序、final 后不得继续事件、时间戳单调；非法事件流用稳定 `VoiceASRError`。
- 空 frame stream 不得凭空产出 final。
- Provider 不持有跨 session 状态；events 不应在多个调用间被破坏性消费。

## 术语纠错

- 默认只使用 T2 UTF-8 词典做确定性精确别名替换；空显式词典保持为空。
- 删除“只 assemble Prompt 并记录模板元数据、但不调用模型”的伪路径。
- 可选模型纠错仅在明确注入 `ModelClient` 时启用；没有 client 时绝不 assemble Prompt/调用模型。
- 启用时必须真实调用 `PromptRuntime.assemble_bundle_for_template(...)` 和 `ModelClient.complete_structured(...)`，使用专用结构 schema；把模型结果与原始 ASR 置信度合并后通过 canonical `VoiceQueryObject.from_dict()` 校验。
- 模型失败、结构错误、空/越界输出或 unsupported 字段时回退确定性词典结果，记录安全 uncertainty/error code；不得记录完整 prompt/messages/raw model text。
- 模型只能建议文本纠错/澄清，不可提供航空事实、证据 ID 或最终检索计划；意图/实体仍由 P1 `QueryUnderstandingService` 重新计算。

## VoiceQueryNormalizer

- 返回 `voice.contracts.VoiceQueryObject`，删除本地重复 dataclass。
- 输入必须是 canonical `ASRResult`。
- 门控优先级固定并有测试：
  1. `is_final == false` → `asr_not_final`；
  2. 空/仅空白 → `empty_transcript`；
  3. confidence 低于 settings → `low_confidence_asr`；
  4. 场景指代缺失/歧义 → `missing_scene_object`/`ambiguous_scene_reference`。
- partial 可以生成安全 `PreRetrievalResult` ID/审计 token 的占位引用，但 `final_retrieval_plan` 必须为空且标记不可作为最终事实来源；不得构造 `TextQueryRequest`。
- 对最终且通过 ASR 基础门控的文本，调用现有 `QueryUnderstandingService.understand_query()` 和 `SceneBinder.bind_scene_reference()`，回填：intent、target_entity、target_aircraft、target_component、scene_object_id、scene candidates、澄清状态。
- 无 scene_state 但文本含“这个/那个/它/this/that”等指代时，必须通过 SceneBinder 等价公共逻辑得到 `missing_scene_object`，不能假定对象。
- 唯一场景候选可绑定；多候选必须澄清。
- `final_retrieval_plan` 只写安全的“eligible/source”审计信息，不含 evidence/事实，不执行检索；T5 才构造正式 `TextQueryRequest`。
- corrections/metadata 仅含规则、ID、长度、uncertainty 和 prompt provenance（仅真实模型调用时），不含完整 prompt、音频或私人原文副本。

## TDD 与验证

1. 先写红测覆盖：partial/final 序列、空 frame、final 后事件、复用调用；航道比/鸡翼/C 九一九/A G 六百；partial、低置信、空文本；无场景指代、唯一/多候选；P1 intent/entities；模型成功、非法输出回退、无 client 不 assemble Prompt。
2. 红测：
   `python -m pytest tests/unit/voice/test_streaming_asr.py tests/unit/voice/test_query_normalizer.py tests/unit/input -q`
3. 实现后重跑目标测试。
4. 回归：全部 unit voice、voice integration/E2E、prompt runtime、contract/core/encoding。
5. compileall 与静态扫描：
   - `ASRResult` 和 `VoiceQueryObject` 各仅一个 dataclass 定义；
   - normalizer/terminology 不导入 retrieval/generator/memory；
   - 不存在 metadata-only Prompt 调用；
   - partial 路径不引用 `TextQueryRequest`/AppPipeline。
6. 更新 STATUS/report/ledger，diff check，提交 `refactor: add streaming ASR normalization`。

## 完成标准

- 流式 ASR 公共协议真实可执行且 partial/final 有序。
- canonical normalizer 完整复用 P1 场景/意图能力。
- 四类正式检索门控在 T4 输出边界生效。
- 确定性词典为默认；模型纠错失败安全回退且不成为事实来源。
- 不再存在重复 query/ASR 契约或 Prompt 元数据伪执行路径。
