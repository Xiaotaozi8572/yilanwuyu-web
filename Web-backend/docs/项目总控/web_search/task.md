# 联网搜索兜底功能 — 任务拆解

## 文档依据

本文是联网搜索兜底功能（Web Search Fallback）的任务拆解文档。当本地知识库检索无结果时，自动通过 DuckDuckGo Instant Answer API 联网搜索，并将搜索结果注入证据包供 LLM 生成答案。

本功能编号为 **W0–W7**（W = Web Search），与历史 P0–P8 和 G0–G8 并行，不覆盖已有验收记录。

## 术语

- **KB**：本地知识库（Knowledge Base）
- **WS**：联网搜索（Web Search）
- **DDG**：DuckDuckGo Instant Answer API
- **EvidencePackage**：证据包，检索结果的标准容器

## 任务总览

| 编号 | 任务名称 | 优先级 | 依赖 | 预计改动文件数 |
|------|---------|--------|------|--------------|
| W0 | 配置与契约扩展 | 高 | 无 | 3 |
| W1 | WebSearchClient 模块 | 高 | W0 | 2 |
| W2 | 检索控制器集成 | 高 | W0, W1 | 3 |
| W3 | Prompt 资产创建 | 高 | 无 | 2 |
| W4 | 生成管线适配 | 高 | W0, W3 | 2 |
| W5 | Agent 运行时贯通 | 高 | W2, W4 | 2 |
| W6 | 集成测试 | 中 | W0–W5 | 2 |
| W7 | 全量回归与收口 | 中 | W0–W6 | 2 |

---

## W0：配置与契约扩展

### 目标
新增 `WebSearchSettings` 配置类，在 `EvidencePackage` 中新增 `web_search_used` 和 `web_search_query` 字段，在 `rag.yaml` 中新增 `web_search` 配置节。

### 优先级
高

### 改动范围
- `configs/rag.yaml`：新增 `web_search` 配置节
- `src/core/runtime_settings.py`：新增 `WebSearchSettings` dataclass，扩展 `RetrievalSettings`
- `src/core/contracts.py`：`EvidencePackage` 新增 2 个字段

### 验收标准
1. `WebSearchSettings` 可从 `rag.yaml` 正确加载，默认值 `enabled=True, timeout=10, max_results=5, provider='duckduckgo'`
2. `EvidencePackage` 新增字段 `web_search_used: bool = False` 和 `web_search_query: str | None = None`，不影响现有序列化
3. 现有单元测试全部通过，无破坏性变更

### 依赖
无

---

## W1：WebSearchClient 模块

### 目标
实现 `WebSearchClient` 类，封装 DuckDuckGo Instant Answer API 调用，返回结构化搜索结果列表。

### 优先级
高

### 改动范围
- `src/knowledge/web_search.py`（新增）：`WebSearchClient` 类 + `WebSearchResult` dataclass
- `tests/unit/knowledge/test_web_search.py`（新增）：6 个单元测试

### 验收标准
1. `WebSearchClient.search(query)` 在正常网络下返回 `list[WebSearchResult]`（title, url, snippet, source）
2. 空查询返回空列表，不抛异常
3. 网络超时/错误返回空列表，不抛异常
4. `web_search.enabled=False` 时 `search()` 直接返回空列表
5. 6 个单元测试全部通过

### 依赖
W0（需要 `WebSearchSettings` 配置）

---

## W2：检索控制器集成

### 目标
在 `RetrievalController.retrieve_evidence()` 中，当 `missing_evidence` 包含 `"no_qualified_evidence"` 时，触发 web search 兜底，将 web 结果转换为 `EvidenceChunk` 并注入证据包。

### 优先级
高

### 改动范围
- `src/knowledge/retrieval_controller.py`：`retrieve_evidence()` 方法末尾增加 web search 兜底逻辑
- `src/knowledge/evidence_package.py`：`EvidencePackageBuilder` 支持 `web_search_used` 标记
- `src/services/app_pipeline.py`：`AppPipeline.__init__()` 中传递 `web_search_settings` 到 `RetrievalController`

### 验收标准
1. KB 未命中时自动调用 `WebSearchClient.search()` 并返回 web 结果
2. `EvidencePackage.missing_evidence` 包含 `"web_search_fallback"` 而非 `"no_qualified_evidence"`
3. `EvidencePackage.web_search_used=True`
4. `web_search.enabled=False` 时保持原有行为不变
5. web search 失败时优雅降级为原有 clarification
6. 现有知识库测试全部通过

### 依赖
W0（配置与契约）、W1（WebSearchClient）

---

## W3：Prompt 资产创建

### 目标
创建 `web_search_answer` prompt 模板，用于联网搜索场景的答案生成。模板应明确指示 LLM 标注信息来源为互联网搜索。

### 优先级
高

### 改动范围
- `assets/prompts/web_search_answer/asset.json`（新增）：prompt 模板定义
- `assets/prompts/web_search_answer/versions/v1.json`（新增）：版本快照

### 验收标准
1. `asset.json` 包含 `template_id="web_search_answer"`、`system` 和 `user` 角色消息
2. system prompt 包含"以下信息来自互联网公开搜索结果，非内部知识库权威来源"标注指令
3. user prompt 包含 `{query}` 和 `{web_search_results}` 变量占位符
4. `PromptRuntime` 能正确加载该模板

### 依赖
无

---

## W4：生成管线适配

### 目标
修改 `DraftingCoordinator.generate()` 和 `AnswerGenerationPipeline.generate_outcome()`，当证据来自 web search 时，不进入 clarification 路径，而是调用 LLM 基于 web 结果生成答案。

### 优先级
高

### 改动范围
- `src/generation/drafting.py`：`DraftingCoordinator.generate()` 中增加 web search 分支判断
- `src/generation/pipeline.py`：`generate_outcome()` 中传递 web search 标记到 prompt 路由

### 验收标准
1. `EvidencePackage.web_search_used=True` 时，`plan.allow_definitive_answer=False` 不进入 clarification 路径
2. LLM 使用 `web_search_answer` 模板生成答案
3. 生成的 `ParsedAnswerDraft` 中 `claim_candidates` 的 `source_origin` 标注为 `SourceOrigin.WEB_SEARCH`（需在 `answer_contracts.py` 新增枚举值）
4. 无 web search 时，原有行为完全不变
5. 现有生成管线测试全部通过

### 依赖
W0（EvidencePackage 字段）、W3（Prompt 模板）

---

## W5：Agent 运行时贯通

### 目标
在 `LangGraphAgentRuntime` 的 `RetrieveEvidenceNode` 和 `GenerateDraftNode` 中透传 web search 标记，确保 web search 状态在整个 agent 图节点间正确传递。

### 优先级
高

### 改动范围
- `src/agent/langgraph_runtime.py`：`_retrieve_evidence()` 和 `_generate_draft()` 节点方法
- `src/services/app_pipeline.py`：确保 `RetrievalController` 使用 web search 配置

### 验收标准
1. `RetrieveEvidenceNode` 能正确返回带 `web_search_used=True` 的 `EvidencePackage`
2. `GenerateDraftNode` 能正确消费 `web_search_used` 标记，传递给生成管线
3. 完整链路（检索 → 生成 → 自检）不因 web search 标记而中断
4. 现有 agent 测试全部通过

### 依赖
W2（检索控制器集成）、W4（生成管线适配）

---

## W6：集成测试

### 目标
编写端到端集成测试，覆盖 KB 未命中 → web search 兜底 → 答案生成的完整链路。

### 优先级
中

### 改动范围
- `tests/integration/rag_pipeline/test_web_search_fallback.py`（新增）：5 个集成测试
- `tests/unit/knowledge/test_web_search.py`（已在 W1 创建，W6 可能补充）

### 验收标准
1. `test_kb_miss_triggers_web_search`：KB 未命中 → 触发 web search → 返回 web 结果
2. `test_web_search_result_in_generation`：web 结果进入生成管线并产生答案
3. `test_answer_marks_web_source`：生成的答案包含 web 来源标注
4. `test_web_search_disabled_no_fallback`：禁用 web search 时保持原有 clarification
5. `test_web_search_failure_no_crash`：web search 失败时优雅降级
6. 5 个集成测试全部通过

### 依赖
W0–W5 全部完成

---

## W7：全量回归与收口

### 目标
运行全量测试套件，确保无回归；更新 `STATUS.md` 记录本次功能；检查依赖声明。

### 优先级
中

### 改动范围
- `pyproject.toml`：确认 `httpx` 已显式声明
- `docs/项目总控/STATUS.md`：追加 W0–W7 验收记录

### 验收标准
1. `pytest tests/ -q` 全部通过（含新增测试，退出码 0）
2. `python scripts/validate_deployment.py` 通过
3. `STATUS.md` 中记录 W0–W7 完成状态
4. 无遗留的 `# TODO`、`# FIXME` 或注释掉的死代码

### 依赖
W0–W6 全部完成

---

## 执行顺序

```
W0 ──┬── W1 ──┬── W2 ──┬── W5 ──┬── W6 ── W7
     │         │         │         │
     └── W3 ──┴── W4 ────┘         │
                                   │
（W0 和 W3 可并行，其余按拓扑顺序）
```

## 回滚方式

每个 W 任务完成后，通过 `git stash` 或独立 commit 可在不影响其他任务的情况下回滚。回滚某任务时，其下游依赖任务也必须回滚。