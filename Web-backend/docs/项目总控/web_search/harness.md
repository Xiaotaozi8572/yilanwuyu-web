# 联网搜索兜底功能 — 约束规范

## 文档依据

本文与 [task.md](./task.md) 和 [spec.md](./spec.md) 一一对应，为每个 W 任务定义安全边界、允许修改范围、禁止触碰项和验证门禁。所有开发必须遵守本文约束，违反任一条目必须立即停止并在 `STATUS.md` 中记录。

## 公共禁项（适用于所有 W 任务）

1. 禁止读取真实密钥、连接真实生产数据库或外部生产服务
2. 禁止破坏 `TextQueryRequest`、`TextQueryResponse.to_dict()`、CLI 外部契约
3. 禁止修改历史 P0–P8 和 G0–G8 的验收结论
4. 禁止新增大型依赖（新增依赖仅限于 `httpx`，且 `httpx` 已由 langsmith 间接引入）
5. 禁止把 Prompt、记忆、反馈或模型常识作为航空事实
6. 禁止绕过 `evidence_package` 直接生成航空事实
7. 禁止硬编码核心业务规则、阈值、Provider、模型名、top_k
8. 禁止将多模块逻辑混写到单一巨型文件中
9. 禁止跳过测试、日志、异常处理和配置管理
10. 禁止留下冗余代码、无用接口、注释掉的死代码或 `# TODO` / `# FIXME` 标记

## 全局验证命令

每完成一个 W 任务后，必须至少运行以下命令之一确认无回归：

```bash
# 单元测试
python -m pytest tests/unit/ -q --tb=short

# 全量测试
python -m pytest tests/ -q --tb=short

# 部署验证
python scripts/validate_deployment.py
```

---

## W0：配置与契约扩展

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `configs/rag.yaml` | 修改 | 新增 `web_search` 配置节 |
| `src/core/runtime_settings.py` | 修改 | 新增 `WebSearchSettings` dataclass，扩展 `RetrievalSettings` |
| `src/core/contracts.py` | 修改 | `EvidencePackage` 新增 `web_search_used` 和 `web_search_query` 字段 |

### 禁止修改

- 上述文件之外的任何文件
- `EvidencePackage` 的已有字段类型、默认值或顺序
- `RetrievalSettings` 的已有字段（只能追加，不能修改或删除）
- `configs/rag.yaml` 中 `web_search` 之外的任何配置节

### 备份要求

```bash
git stash  # 或 git commit 当前修改
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 0.1 | `rag.yaml` 配置可正确加载 | `python -c "import yaml; cfg = yaml.safe_load(open('configs/rag.yaml')); assert cfg['rag']['web_search']['enabled'] == True"` |
| 0.2 | `WebSearchSettings` 可导入 | `python -c "from core.runtime_settings import WebSearchSettings"` |
| 0.3 | `EvidencePackage` 新字段存在 | `python -c "from core.contracts import EvidencePackage; ep = EvidencePackage(); assert ep.web_search_used == False; assert ep.web_search_query is None"` |
| 0.4 | 核心单元测试无回归 | `python -m pytest tests/unit/core/ -v --tb=short` |

### 单次提交边界

W0 的所有修改必须在一个 commit 中完成，commit message 格式：

```
feat(W0): 新增 WebSearchSettings 配置与 EvidencePackage 契约扩展
```

---

## W1：WebSearchClient 模块

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `src/knowledge/web_search.py` | 新增 | `WebSearchClient` 类 + `WebSearchResult` dataclass |
| `tests/unit/knowledge/test_web_search.py` | 新增 | 6 个单元测试 |

### 禁止修改

- 上述文件之外的任何文件
- 不得修改 `src/knowledge/` 下的已有文件
- 不得引入除 `httpx`（已在依赖中）之外的任何新依赖
- 不得在 `WebSearchClient` 中硬编码 API key 或使用付费 API

### 备份要求

```bash
git status  # 确认当前工作区干净
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 1.1 | 模块可导入 | `python -c "from knowledge.web_search import WebSearchClient, WebSearchResult"` |
| 1.2 | 6 个单元测试全部通过 | `python -m pytest tests/unit/knowledge/test_web_search.py -v --tb=short` |
| 1.3 | 核心测试无回归 | `python -m pytest tests/unit/core/ -v --tb=short` |

### 单次提交边界

```
feat(W1): 实现 WebSearchClient 模块（DuckDuckGo Instant Answer API）
```

---

## W2：检索控制器集成

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `src/knowledge/retrieval_controller.py` | 修改 | `retrieve_evidence()` 增加 web search 兜底逻辑 |
| `src/knowledge/evidence_package.py` | 修改 | `EvidencePackageBuilder` 支持 `web_search_used` 标记 |
| `src/services/app_pipeline.py` | 修改 | `AppPipeline.__init__()` 传递 `web_search_client` |

### 禁止修改

- 上述文件之外的任何文件
- `RetrievalController.retrieve_evidence()` 中 web search 兜底逻辑之外的任何代码路径
- `EvidencePackageBuilder.build()` 的已有参数签名
- 不得修改检索通道（keyword/dense/table/parent）的选择逻辑
- 不得修改 `EvidenceGate` 的过滤规则
- 不得修改 `RetrievalPlanner` 的检索计划生成逻辑

### 备份要求

```bash
git stash  # 保存当前修改
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 2.1 | `RetrievalController` 可导入 | `python -c "from knowledge.retrieval_controller import RetrievalController"` |
| 2.2 | `AppPipeline` 可导入 | `python -c "from services.app_pipeline import AppPipeline"` |
| 2.3 | 知识库单元测试无回归 | `python -m pytest tests/unit/knowledge/ -v --tb=short` |
| 2.4 | 知识库集成测试无回归 | `python -m pytest tests/integration/rag_pipeline/ -v --tb=short -k "not web_search"` |
| 2.5 | 无硬编码 web search 配置 | `grep -r "duckduckgo\|api.duckduckgo" src/knowledge/retrieval_controller.py` 应只在 `web_search.py` 中出现 |

### 单次提交边界

```
feat(W2): 检索控制器集成 web search 兜底逻辑
```

---

## W3：Prompt 资产创建

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `assets/prompts/web_search_answer/asset.json` | 新增 | prompt 模板定义 |
| `assets/prompts/web_search_answer/versions/v1.json` | 新增 | 版本快照 |

### 禁止修改

- 上述文件之外的任何文件
- 不得修改已有 prompt 模板（`assets/prompts/` 下的其他目录）
- 不得在 web_search_answer 模板中写入航空事实
- 不得修改 `src/prompts/` 下的任何代码

### 备份要求

无需备份（仅新增文件）。

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 3.1 | `asset.json` 格式正确 | `python -c "import json; a = json.load(open('assets/prompts/web_search_answer/asset.json')); assert 'template_id' in a"` |
| 3.2 | `v1.json` 格式正确 | `python -c "import json; v = json.load(open('assets/prompts/web_search_answer/versions/v1.json')); assert v['status'] == 'approved'"` |
| 3.3 | Prompt 测试无回归 | `python -m pytest tests/unit/prompts/ -v --tb=short` |

### 单次提交边界

```
feat(W3): 新增 web_search_answer prompt 模板资产
```

---

## W4：生成管线适配

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `src/core/answer_contracts.py` | 修改 | `SourceOrigin` 新增 `WEB_SEARCH` 枚举值 |
| `src/generation/drafting.py` | 修改 | `DraftingCoordinator.generate()` 增加 web search 分支 |
| `src/generation/evidence_sketch.py` | 修改 | `EvidenceSketch` 新增 `web_search_used` 字段 |
| `src/generation/pipeline.py` | 修改 | `generate_outcome()` 传递 web search 标记到 prompt 路由 |

### 禁止修改

- 上述文件之外的任何文件
- `DraftingCoordinator.generate()` 中 safety template 和 parameter_fact 分支的逻辑
- `DeterministicFallbackGenerator` 的任何方法
- `ModelDraftGenerator` 的任何方法
- `AnswerGenerationPipeline` 中 routing、sketch、plan、outline、draft 步骤的顺序
- 不得修改 `ClaimNormalizer`、`CitationBinder`、`GenerationTraceBuilder`

### 备份要求

```bash
git stash
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 4.1 | `SourceOrigin.WEB_SEARCH` 存在 | `python -c "from core.answer_contracts import SourceOrigin; assert SourceOrigin.WEB_SEARCH.value == 'web_search'"` |
| 4.2 | `EvidenceSketch` 新字段存在 | `python -c "from generation.evidence_sketch import EvidenceSketch; assert hasattr(EvidenceSketch(), 'web_search_used')"` |
| 4.3 | 生成单元测试无回归 | `python -m pytest tests/unit/generation/ -v --tb=short` |
| 4.4 | 生成集成测试无回归 | `python -m pytest tests/integration/answer_pipeline/ -v --tb=short -k "not web_search"` |
| 4.5 | clarification 路径未受影响 | `python -m pytest tests/unit/generation/ -v --tb=short -k "clarification or fallback"` |

### 单次提交边界

```
feat(W4): 生成管线适配 web search 答案生成路径
```

---

## W5：Agent 运行时贯通

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `src/agent/langgraph_runtime.py` | 修改 | `_retrieve_evidence()` 和 `_generate_draft()` 透传 web search 标记 |
| `src/services/app_pipeline.py` | 修改 | 确保 `RetrievalController` 使用 web search 配置 |

### 禁止修改

- 上述文件之外的任何文件
- `LangGraphAgentRuntime` 中 `run_text_query()` 的主流程
- `RetrieveEvidenceNode` 和 `GenerateDraftNode` 之外的任何图节点
- 不得修改 `AgentState` 的已有字段
- 不得修改 `AgentActionExecutor` 或 `RetrievalActionService`
- 不得修改 `SelfCheckNode` 的任何逻辑

### 备份要求

```bash
git stash
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 5.1 | `LangGraphAgentRuntime` 可导入 | `python -c "from agent.langgraph_runtime import LangGraphAgentRuntime"` |
| 5.2 | Agent 单元测试无回归 | `python -m pytest tests/unit/agent/ -v --tb=short` |
| 5.3 | Agent 集成测试无回归 | `python -m pytest tests/integration/app_loop/ -v --tb=short -k "not web_search"` |
| 5.4 | 图编译无错误 | `python -c "from services.app_pipeline import AppPipeline; p = AppPipeline(); print('graph compiled'); p.close()"` |

### 单次提交边界

```
feat(W5): Agent 运行时贯通 web search 标记传递
```

---

## W6：集成测试

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `tests/integration/rag_pipeline/test_web_search_fallback.py` | 新增 | 5 个集成测试 |
| `tests/unit/knowledge/test_web_search.py` | 可修改 | 允许补充测试用例（但不得删除已有测试） |

### 禁止修改

- 上述文件之外的任何文件
- 不得修改已有测试文件（除非修复 W0–W5 引入的回归）
- 不得为了测试通过而修改业务代码
- 不得在测试中使用真实网络请求（必须 mock `urlopen`）

### 备份要求

无需备份（仅新增文件）。

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 6.1 | 5 个集成测试全部通过 | `python -m pytest tests/integration/rag_pipeline/test_web_search_fallback.py -v --tb=short` |
| 6.2 | 单元测试全部通过 | `python -m pytest tests/unit/knowledge/test_web_search.py -v --tb=short` |
| 6.3 | 无真实网络请求 | `grep -r "urlopen\|requests.get\|httpx.get" tests/` 应只在 mock 上下文中出现 |

### 单次提交边界

```
test(W6): 新增 web search 兜底集成测试
```

---

## W7：全量回归与收口

### 允许修改的文件

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `pyproject.toml` | 可修改 | 确认 `httpx` 显式声明 |
| `docs/项目总控/STATUS.md` | 修改 | 追加 W0–W7 验收记录 |

### 禁止修改

- 上述文件之外的任何文件
- 不得修改 `pyproject.toml` 中除 `httpx` 声明之外的任何依赖
- 不得修改 `STATUS.md` 中历史 P0–P8 和 G0–G8 的验收记录
- 不得为了测试通过而修改业务代码

### 备份要求

```bash
git stash
```

### 验证门禁

| # | 检查项 | 命令 |
|---|--------|------|
| 7.1 | 全量测试通过 | `python -m pytest tests/ -q --tb=short` |
| 7.2 | 部署验证通过 | `python scripts/validate_deployment.py` |
| 7.3 | 编译检查通过 | `python -m py_compile src/knowledge/web_search.py` |
| 7.4 | 无死代码 | `grep -r "TODO\|FIXME\|HACK" src/knowledge/web_search.py` 应无输出 |
| 7.5 | `httpx` 已声明 | `grep httpx pyproject.toml` 应有输出 |
| 7.6 | STATUS.md 已更新 | `grep "W0–W7" docs/项目总控/STATUS.md` 应有输出 |

### 单次提交边界

```
chore(W7): 全量回归验证与 STATUS.md 收口
```

---

## 回滚策略

每个 W 任务对应一个独立的 git commit。回滚方式：

| 回滚范围 | 操作 |
|---------|------|
| 仅回滚 W7 | `git revert <W7-commit>` |
| 仅回滚 W6 | `git revert <W6-commit>` |
| 回滚 W5 | `git revert <W5-commit> <W6-commit> <W7-commit>`（需同时回滚下游） |
| 全部回滚 | `git revert <W7-commit> <W6-commit> ... <W0-commit>` |

禁止使用 `git reset --hard` 或 `git push --force`。

## 停止条件

遇到以下任一情况必须立即停止，不得继续：

1. 同一测试连续三次失败且无法定位原因
2. 需要修改 W0–W7 允许范围之外的文件
3. 需要引入新的外部依赖（`httpx` 除外）
4. 当前实现会破坏已有接口契约（已有测试失败）
5. 需要访问真实密钥或外部生产服务
6. `task.md`、`spec.md`、`harness.md` 三者出现明显冲突
7. 需要修改 `EvidenceGate` 的过滤规则来让 web search 结果通过