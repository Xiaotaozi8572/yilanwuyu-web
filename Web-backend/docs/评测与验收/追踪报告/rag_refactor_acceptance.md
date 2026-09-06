# RAG 知识检索模块工程化重构验收记录

日期：2026-07-10
评测产物：`docs/评测与验收/评测报告/rag_refactor_eval.json`

## 验收结论

`rag_refactor` 固定评测共 8 个案例，8 个通过，pass rate 为 1.0。所有知识入库均通过 Task 11 的 `ingest_source()` 正式入口，知识库和记忆库均位于运行时 `TemporaryDirectory`，未写入正式数据库。

覆盖案例为：C919 机翼升力定义、C919 发动机涵道比、AG600 船型机身场景绑定、升力与推力对比、缺少审核参数、冲突参数来源、低置信语音、事实挑战后的定向补检索。每个案例均保存预期/实际通道、预期/实际 Gate、所需证据类型和禁止声明。

## 已落地能力

- SQLite 持久化来源、父子片段、FTS5/BM25、dense embedding 元数据、场景、视觉页面、图关系及索引版本。
- 配置驱动 L1—L5 规划、多查询变体、RRF、七特征重排、confident/weak/conflict/unclear 四态 Gate。
- 场景对象绑定、受审核来源约束的有界图遍历、事实挑战后的结构化定向补检索。
- `pypdf 6.14.2` 可提取 PDF 页面文本、页码与来源位置；视觉证据必须满足审核与文本交叉验证边界。
- trace 导出只保留复杂度、通道/命中数、RRF 排名、重排特征合计、来源权威标签、Gate、缺失/冲突代码、补检索差异和延迟。

## 明确能力边界

- 当前 active profile 使用离线 Mock embedding；生产 profile 明确拒绝 Mock。尚未接入外部生产 embedding Provider 或生产级向量数据库。
- 当前没有 OCR、真实版面分析、视觉 embedding、ColPali 或 VisRAG；`mock_pdf_stub`/`mock_visual_adapter` 仅如实标识离线测试适配器。
- 当前图通道是受审核边/来源约束的有界图遍历，不是 GraphRAG。
- 缺少审核参数时 Gate 为 weak；审核来源参数冲突时 Gate 为 conflict，不得猜测或任选数值。
- 低置信语音在标准化阶段澄清，不进入 RAG。

## 数据与审计安全

评测 JSON 与 Markdown trace 不保存完整受保护来源文本、私有记忆正文、Prompt 正文或原始音频。旧 `EvidenceRanker`、`SimpleVectorIndex`、`simple_token_similarity` 和 controller 手工 `add_chunks` 路径已移除；测试种子逻辑位于 `tests/helpers/`，生产配置不可选择。

## 外部验收 concern

`scripts/validate_knowledge_base.py --config configs/rag.yaml` 是只读检查，当前已知退出码为 1：正式知识库存在两个 parentless chunks，且 active index version 数为 0。先前披露的 `source_08a01b3c4205` 与 `source_071d4050a264` 保持原状；本任务未清理、迁移或 rebuild 正式数据库。该项必须由主控如实保留，除非用户另行授权数据操作。

## 本任务验证

- `scripts/run_eval.py --suite rag_refactor`：8/8，pass rate 1.0。
- Task 12 定向 eval/trace 测试：8 passed。
- 完整固定范围与集成/E2E 结果以本任务提交前 fresh 验证及主控最终全量验收记录为准。
