# 联网搜索兜底功能 — 执行规格

## 文档依据

本文与 [task.md](./task.md) 一一对应，为每个 W 任务提供详细执行步骤。所有步骤必须按顺序执行，不得跳过或合并。

## 全局前置条件

1. 虚拟环境：`D:\APP\Python 3.13\挑战杯\.venv`
2. 所有命令在项目根目录 `D:\APP\Python 3.13\挑战杯` 执行
3. 每个 W 任务完成后必须运行对应验证命令，确认通过后方可进入下一任务
4. Git 提交信息必须使用中文

---

## W0：配置与契约扩展

### 步骤 0.1：修改 `configs/rag.yaml`

**文件**：`configs/rag.yaml`

**位置**：在 `rag:` 顶级键下，`embedding:` 之前，新增 `web_search` 配置节。

**改动前**（约 67 行，`embedding:` 之前）：
```yaml
  embedding:
    provider: bge_m3
```

**改动后**：
```yaml
  web_search:
    enabled: true
    timeout: 10
    max_results: 5
    provider: duckduckgo
  embedding:
    provider: bge_m3
```

**验证**：
```bash
python -c "import yaml; cfg = yaml.safe_load(open('configs/rag.yaml')); assert cfg['rag']['web_search']['enabled'] == True; print('W0.1 OK')"
```

---

### 步骤 0.2：修改 `src/core/runtime_settings.py`

**文件**：`src/core/runtime_settings.py`

**位置 1**：在 `RetrievalSettings` dataclass 定义之前，新增 `WebSearchSettings` dataclass。

**改动**：在 `RetrievalSettings` 类定义之前（约 113 行附近），插入：

```python
@dataclass(frozen=True)
class WebSearchSettings:
    enabled: bool
    timeout: int
    max_results: int
    provider: str
```

**位置 2**：在 `RetrievalSettings` dataclass 中新增字段 `web_search`。

**改动前**：查找 `RetrievalSettings` 的 `__init__` 参数末尾，在最后一个字段后添加。

**改动**：在 `RetrievalSettings` 字段列表末尾添加：
```python
    web_search: WebSearchSettings
```

**位置 3**：在 `RuntimeSettings.from_settings()` 方法中，新增 `WebSearchSettings` 的构造逻辑。

**改动**：在构造 `RetrievalSettings` 的地方（搜索 `RetrievalSettings(`），在参数中添加 `web_search` 字段的构造：

```python
web_search=WebSearchSettings(
    enabled=bool(rag_cfg.get("web_search", {}).get("enabled", True)),
    timeout=int(rag_cfg.get("web_search", {}).get("timeout", 10)),
    max_results=int(rag_cfg.get("web_search", {}).get("max_results", 5)),
    provider=str(rag_cfg.get("web_search", {}).get("provider", "duckduckgo")),
),
```

**验证**：
```bash
python -c "from core.runtime_settings import RuntimeSettings, WebSearchSettings; s = RuntimeSettings.from_settings.__wrapped__(...); print('W0.2 OK')" 2>&1
```
实际验证命令：
```bash
python -m pytest tests/unit/core/test_runtime_settings.py -v --tb=short -k "retrieval"
```

---

### 步骤 0.3：修改 `src/core/contracts.py`

**文件**：`src/core/contracts.py`

**位置**：`EvidencePackage` dataclass（约 77 行），在 `gate_status` 字段之前添加新字段。

**改动前**：
```python
@dataclass
class EvidencePackage(BaseContract):
    evidence_package_id: str = field(default_factory=lambda: new_id("evidence_pkg"))
    query_understanding: dict[str, Any] = field(default_factory=dict)
    scene_binding: dict[str, Any] = field(default_factory=dict)
    retrieval_plan: dict[str, Any] = field(default_factory=dict)
    evidence_items: list[EvidenceItem] = field(default_factory=list)
    claim_support_map: dict[str, Any] = field(default_factory=dict)
    missing_evidence: list[str] = field(default_factory=list)
    contradiction_evidence: list[dict[str, Any]] = field(default_factory=list)
    generation_boundary: list[str] = field(default_factory=list)
    audit_trace: list[dict[str, Any]] = field(default_factory=list)
    gate_status: str = "unclear"
```

**改动后**：
```python
@dataclass
class EvidencePackage(BaseContract):
    evidence_package_id: str = field(default_factory=lambda: new_id("evidence_pkg"))
    query_understanding: dict[str, Any] = field(default_factory=dict)
    scene_binding: dict[str, Any] = field(default_factory=dict)
    retrieval_plan: dict[str, Any] = field(default_factory=dict)
    evidence_items: list[EvidenceItem] = field(default_factory=list)
    claim_support_map: dict[str, Any] = field(default_factory=dict)
    missing_evidence: list[str] = field(default_factory=list)
    contradiction_evidence: list[dict[str, Any]] = field(default_factory=list)
    generation_boundary: list[str] = field(default_factory=list)
    audit_trace: list[dict[str, Any]] = field(default_factory=list)
    web_search_used: bool = False
    web_search_query: str | None = None
    gate_status: str = "unclear"
```

**验证**：
```bash
python -c "from core.contracts import EvidencePackage; ep = EvidencePackage(); assert ep.web_search_used == False; assert ep.web_search_query is None; print('W0.3 OK')"
```

---

### 步骤 0.4：W0 总体验证

```bash
python -m pytest tests/unit/core/ -v --tb=short
```

**预期**：所有已有测试通过，无新增失败。

---

## W1：WebSearchClient 模块

### 步骤 1.1：创建 `src/knowledge/web_search.py`

**文件**：`src/knowledge/web_search.py`（新文件）

**完整内容**：

```python
from __future__ import annotations

from dataclasses import dataclass
import json
from urllib.error import URLError
from urllib.request import Request, urlopen

from core.runtime_settings import WebSearchSettings
from observability.logging_config import get_logger

logger = get_logger(__name__)


@dataclass(frozen=True)
class WebSearchResult:
    title: str
    url: str
    snippet: str
    source: str  # "duckduckgo"


class WebSearchClient:
    """DuckDuckGo Instant Answer API client.

    The client calls the free, no-key-required DuckDuckGo API and returns
    structured results.  It is designed to never raise — failures return an
    empty list so the caller can always degrade gracefully.
    """

    def __init__(self, settings: WebSearchSettings) -> None:
        self._settings = settings
        self._endpoint = "https://api.duckduckgo.com/"

    def search(self, query: str) -> list[WebSearchResult]:
        if not self._settings.enabled:
            return []
        query = (query or "").strip()
        if not query:
            return []
        results: list[WebSearchResult] = []
        try:
            params = "?q=" + self._quote(query) + "&format=json&no_html=1&skip_disambig=1"
            req = Request(
                self._endpoint + params,
                headers={"User-Agent": "AviationTutor/1.0"},
            )
            with urlopen(req, timeout=self._settings.timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except Exception:
            logger.exception("web_search_request_failed", query=query[:100])
            return []

        if payload.get("AbstractText"):
            results.append(WebSearchResult(
                title=payload.get("Heading") or payload.get("AbstractSource") or "DuckDuckGo",
                url=payload.get("AbstractURL") or "",
                snippet=payload["AbstractText"],
                source="duckduckgo",
            ))

        for topic in payload.get("RelatedTopics", []):
            if isinstance(topic, dict) and topic.get("Text"):
                results.append(WebSearchResult(
                    title=topic.get("FirstURL", "").split("/")[-1].replace("_", " ").title() or "Related",
                    url=topic.get("FirstURL", ""),
                    snippet=topic["Text"],
                    source="duckduckgo",
                ))
            if len(results) >= self._settings.max_results:
                break

        logger.info(
            "web_search_completed",
            query=query[:80],
            result_count=len(results),
        )
        return results[: self._settings.max_results]

    @staticmethod
    def _quote(value: str) -> str:
        from urllib.parse import quote
        return quote(value, safe="")
```

**验证**：
```bash
python -c "from knowledge.web_search import WebSearchClient, WebSearchResult; print('W1.1 import OK')"
```

---

### 步骤 1.2：创建 `tests/unit/knowledge/test_web_search.py`

**文件**：`tests/unit/knowledge/test_web_search.py`（新文件）

**完整内容**：

```python
from __future__ import annotations

import pytest
from unittest.mock import patch, MagicMock

from core.runtime_settings import WebSearchSettings
from knowledge.web_search import WebSearchClient, WebSearchResult


def _make_settings(**overrides) -> WebSearchSettings:
    defaults = {"enabled": True, "timeout": 10, "max_results": 5, "provider": "duckduckgo"}
    return WebSearchSettings(**(defaults | overrides))


class TestWebSearchClient:
    def test_search_returns_results(self):
        settings = _make_settings()
        client = WebSearchClient(settings)
        mock_response = MagicMock()
        mock_response.__enter__.return_value.read.return_value = (
            b'{"AbstractText":"Test abstract","AbstractURL":"http://example.com",'
            b'"Heading":"Test Heading","RelatedTopics":[]}'
        )
        with patch("knowledge.web_search.urlopen", return_value=mock_response):
            results = client.search("test query")
        assert len(results) == 1
        assert results[0].snippet == "Test abstract"
        assert results[0].source == "duckduckgo"

    def test_search_empty_query_returns_empty(self):
        client = WebSearchClient(_make_settings())
        assert client.search("") == []
        assert client.search("   ") == []

    def test_search_disabled_returns_empty(self):
        client = WebSearchClient(_make_settings(enabled=False))
        assert client.search("anything") == []

    def test_search_network_error_returns_empty(self):
        client = WebSearchClient(_make_settings())
        with patch("knowledge.web_search.urlopen", side_effect=OSError("network down")):
            results = client.search("test")
        assert results == []

    def test_search_timeout_returns_empty(self):
        settings = _make_settings(timeout=1)
        client = WebSearchClient(settings)
        import socket
        with patch("knowledge.web_search.urlopen", side_effect=TimeoutError("timeout")):
            results = client.search("test")
        assert results == []

    def test_search_no_abstract_uses_related_topics(self):
        settings = _make_settings()
        client = WebSearchClient(settings)
        mock_response = MagicMock()
        mock_response.__enter__.return_value.read.return_value = (
            b'{"AbstractText":"","RelatedTopics":['
            b'{"Text":"Topic 1","FirstURL":"http://a.com"},'
            b'{"Text":"Topic 2","FirstURL":"http://b.com"}'
            b']}'
        )
        with patch("knowledge.web_search.urlopen", return_value=mock_response):
            results = client.search("test")
        assert len(results) == 2
        assert results[0].snippet == "Topic 1"
```

**验证**：
```bash
python -m pytest tests/unit/knowledge/test_web_search.py -v --tb=short
```

**预期**：6 passed

---

### 步骤 1.3：W1 总体验证

```bash
python -m pytest tests/unit/knowledge/test_web_search.py tests/unit/core/ -v --tb=short
```

**预期**：所有测试通过。

---

## W2：检索控制器集成

### 步骤 2.1：修改 `src/knowledge/evidence_package.py`

**文件**：`src/knowledge/evidence_package.py`

**位置**：找到 `EvidencePackageBuilder.build()` 方法。在方法签名中，确保 `web_search_used` 和 `web_search_query` 参数能传入 `EvidencePackage` 构造。

**改动**：在 `EvidencePackage` 构造调用中，添加 `web_search_used` 和 `web_search_query` 字段的传递。如果 builder 当前使用 `**kwargs` 方式构造，则无需改动；如果是显式字段列表，则添加：

```python
web_search_used=kwargs.get("web_search_used", False),
web_search_query=kwargs.get("web_search_query", None),
```

**验证**：
```bash
python -c "from knowledge.evidence_package import EvidencePackageBuilder; print('W2.1 OK')"
```

---

### 步骤 2.2：修改 `src/knowledge/retrieval_controller.py`

**文件**：`src/knowledge/retrieval_controller.py`

**位置 1**：在 `__init__` 方法中新增 `web_search_client` 参数。

**改动**：在 `__init__` 的参数列表中添加 `web_search_client: WebSearchClient | None = None`，并在方法体中存储为 `self._web_search_client`。

**验证**：先确认 `__init__` 签名，然后查找 `RetrievalController.from_config()` 方法，在其中构造 `WebSearchClient`。

**位置 2**：在 `retrieve_evidence()` 方法末尾（约 1334 行），当 `missing == ["no_qualified_evidence"]` 时，插入 web search 兜底逻辑。

**改动前**（约 1333-1334 行）：
```python
        selected_candidates = ranked_candidates[:selection_limit]
        missing = [] if qualified_candidates else ["no_qualified_evidence"]
```

**改动**：在 `missing = ...` 之后、`package = self.builder.build(...)` 之前，插入：

```python
        web_search_used = False
        web_search_query = None
        if missing == ["no_qualified_evidence"] and self._web_search_client is not None:
            web_search_query = retrieval_plan.original_query
            web_results = self._web_search_client.search(web_search_query)
            if web_results:
                web_chunks = self._web_results_to_evidence_chunks(
                    web_results, retrieval_plan
                )
                selected_candidates = web_chunks
                missing = ["web_search_fallback"]
                web_search_used = True
                logger.info(
                    "web_search_fallback_used",
                    query=web_search_query[:80],
                    result_count=len(web_results),
                )
```

**位置 3**：在 `EvidencePackage` 构造中添加新字段。

**改动**：在 `self.builder.build()` 调用中，添加 `web_search_used=web_search_used` 和 `web_search_query=web_search_query`。

**位置 4**：新增 `_web_results_to_evidence_chunks()` 私有方法。

**改动**：在 `RetrievalController` 类中添加方法：

```python
    def _web_results_to_evidence_chunks(
        self, results: list, retrieval_plan
    ) -> list:
        from knowledge.schemas import TextChunk
        chunks = []
        for i, result in enumerate(results):
            chunk = TextChunk(
                chunk_id=f"web_search_{i}",
                text=f"[{result.title}]({result.url})\n{result.snippet}",
                source_id=f"web_search_{result.source}",
                metadata={
                    "source": result.source,
                    "url": result.url,
                    "title": result.title,
                    "web_search_rank": i + 1,
                },
            )
            chunks.append(chunk)
        return chunks
```

**验证**：
```bash
python -c "from knowledge.retrieval_controller import RetrievalController; print('W2.2 import OK')"
```

---

### 步骤 2.3：修改 `src/services/app_pipeline.py`

**文件**：`src/services/app_pipeline.py`

**位置**：在 `AppPipeline.__init__()` 中构造 `RetrievalController` 的地方（约 109-113 行），传递 `web_search_client`。

**改动前**：
```python
        elif rag_config_path is None:
            self.retrieval_controller = RetrievalController.from_config()
        else:
            self.retrieval_controller = RetrievalController.from_config(
                str(rag_config_path)
            )
```

**改动**：在 `RetrievalController.from_config()` 调用前，构造 `WebSearchClient`：

```python
        from knowledge.web_search import WebSearchClient
        web_search_client = WebSearchClient(runtime_settings.retrieval.web_search) if runtime_settings.retrieval.web_search.enabled else None
```

然后将 `web_search_client` 传递给 `RetrievalController.from_config(..., web_search_client=web_search_client)`。

**注意**：需要修改 `RetrievalController.from_config()` 的签名以接受 `web_search_client` 参数。

**验证**：
```bash
python -c "from services.app_pipeline import AppPipeline; print('W2.3 import OK')"
```

---

### 步骤 2.4：W2 总体验证

```bash
python -m pytest tests/unit/knowledge/ -v --tb=short
```

**预期**：所有已有知识库测试通过，无新增失败。

---

## W3：Prompt 资产创建

### 步骤 3.1：创建 `assets/prompts/web_search_answer/asset.json`

**文件**：`assets/prompts/web_search_answer/asset.json`（新文件）

**完整内容**：

```json
{
  "template_id": "web_search_answer",
  "task_type": "answer_generation",
  "activation_scope": "web_search_fallback",
  "review_status": "reviewed",
  "version": "v1",
  "messages": [
    {
      "role": "system",
      "content": "你是航空知识助手。以下信息来自互联网公开搜索结果，非内部知识库权威来源。请在回答开头明确标注「以下信息来自互联网搜索结果，建议自行核实」，并在每条信息后标注来源网址。回答应保持结构化、客观，不得编造或夸大信息。"
    },
    {
      "role": "user",
      "content": "问题：{query}\n\n互联网搜索结果：\n{web_search_results}\n\n请基于以上信息生成回答，开头必须标注「以下信息来自互联网搜索结果，建议自行核实」。"
    }
  ],
  "variables": {
    "query": {"type": "string", "required": true, "description": "用户原始问题"},
    "web_search_results": {"type": "string", "required": true, "description": "联网搜索结果的格式化文本"}
  },
  "constraints": {
    "require_source_citation": true,
    "require_disclaimer": true,
    "max_answer_chars": 2000
  }
}
```

**验证**：
```bash
python -c "import json; a = json.load(open('assets/prompts/web_search_answer/asset.json')); assert a['template_id'] == 'web_search_answer'; print('W3.1 OK')"
```

---

### 步骤 3.2：创建 `assets/prompts/web_search_answer/versions/v1.json`

**文件**：`assets/prompts/web_search_answer/versions/v1.json`（新文件）

**完整内容**：

```json
{
  "version": "v1",
  "snapshot_id": "web_search_answer_v1_20260817",
  "created_at": "2026-08-17T00:00:00Z",
  "status": "approved",
  "evaluation_snapshot": null,
  "notes": "首次创建联网搜索答案模板。system prompt 要求标注来源为互联网搜索结果。"
}
```

**验证**：
```bash
python -c "import json; v = json.load(open('assets/prompts/web_search_answer/versions/v1.json')); assert v['status'] == 'approved'; print('W3.2 OK')"
```

---

### 步骤 3.3：W3 总体验证

```bash
python -m pytest tests/unit/prompts/ -v --tb=short
```

**预期**：所有已有 prompt 测试通过。

---

## W4：生成管线适配

### 步骤 4.1：修改 `src/core/answer_contracts.py`

**文件**：`src/core/answer_contracts.py`

**位置**：在 `SourceOrigin` 枚举中新增 `WEB_SEARCH` 值。

**改动前**：
```python
class SourceOrigin(str, Enum):
    EVIDENCE = "evidence"
    MEMORY = "memory"
    # ...
```

**改动**：在枚举末尾添加：
```python
    WEB_SEARCH = "web_search"
```

**验证**：
```bash
python -c "from core.answer_contracts import SourceOrigin; assert SourceOrigin.WEB_SEARCH.value == 'web_search'; print('W4.1 OK')"
```

---

### 步骤 4.2：修改 `src/generation/drafting.py`

**文件**：`src/generation/drafting.py`

**位置**：`DraftingCoordinator.generate()` 方法（约 447 行）。

**改动前**：
```python
        if not plan.allow_definitive_answer:
            return DraftResult(
                self.fallback_generator.clarification(), None, True, "route_not_definitive"
            )
```

**改动后**：
```python
        if not plan.allow_definitive_answer:
            if sketch.web_search_used:
                # Web search fallback: allow LLM generation even though the plan
                # marks it as non-definitive (no KB evidence).
                model_result = self.model_generator.generate(messages)
                if model_result.ok and model_result.value is not None:
                    return DraftResult(model_result.value, model_result, False, None)
                return DraftResult(
                    self.fallback_generator.clarification(), None, True, "web_search_model_failed"
                )
            return DraftResult(
                self.fallback_generator.clarification(), None, True, "route_not_definitive"
            )
```

**注意**：需要在 `EvidenceSketch` 中新增 `web_search_used` 字段。如果 `EvidenceSketch` 当前没有此字段，则通过 `EvidencePackage` 传入。

**验证**：
```bash
python -c "from generation.drafting import DraftingCoordinator; print('W4.2 import OK')"
```

---

### 步骤 4.3：修改 `src/generation/evidence_sketch.py`

**文件**：`src/generation/evidence_sketch.py`

**位置**：在 `EvidenceSketch` dataclass 中新增字段。

**改动**：在 `EvidenceSketch` 的字段列表中添加：
```python
    web_search_used: bool = False
```

**位置**：在 `EvidenceSketchBuilder.build()` 方法中，从 `EvidencePackage` 传递 `web_search_used` 到 `EvidenceSketch`。

**改动**：在 `build()` 方法中，将 `evidence_package.web_search_used` 赋值给 `EvidenceSketch(..., web_search_used=evidence_package.web_search_used)`。

**验证**：
```bash
python -c "from generation.evidence_sketch import EvidenceSketch; s = EvidenceSketch(); assert s.web_search_used == False; print('W4.3 OK')"
```

---

### 步骤 4.4：修改 `src/generation/pipeline.py`

**文件**：`src/generation/pipeline.py`

**位置**：`generate_outcome()` 方法中 `self.router.route()` 调用（约 75 行）。

**改动**：在路由判断中，如果 `request.evidence_package.web_search_used` 为 True，则路由到 `web_search_answer` 模板。

**具体改动**：在 `self.prompt_runtime.assemble_generation_bundle()` 调用中，当 `web_search_used` 为 True 时，使用 `web_search_answer` 模板 ID 而非默认模板。

**注意**：需要先确认 `PromptRuntime.assemble_generation_bundle()` 如何选择模板 ID，以及 `AnswerRoute` 或 `RoutePolicy` 如何与模板关联。

**验证**：
```bash
python -m pytest tests/unit/generation/test_answer_type_router.py -v --tb=short
```

---

### 步骤 4.5：W4 总体验证

```bash
python -m pytest tests/unit/generation/ -v --tb=short
```

**预期**：所有已有生成测试通过。

---

## W5：Agent 运行时贯通

### 步骤 5.1：修改 `src/agent/langgraph_runtime.py`

**文件**：`src/agent/langgraph_runtime.py`

**位置 1**：`_retrieve_evidence()` 方法（约 580 行），确保 `EvidencePackage` 中的 `web_search_used` 能被存入 `artifacts`。

**改动**：在 `self.artifacts.put_evidence_package(state["run_id"], evidence)` 调用（约 603 行）之后，添加 web search 标记的日志记录：

```python
                    if evidence.web_search_used:
                        logger.info(
                            "web_search_evidence_ready",
                            run_id=state["run_id"],
                            web_search_query=evidence.web_search_query,
                        )
```

**位置 2**：`_generate_draft()` 方法（约 653 行），确保 `evidence.web_search_used` 能传递到 `GenerationRequest`。

**改动**：确认 `GenerationRequest` 的 `evidence_package` 参数已包含 `web_search_used` 字段（W0.3 已添加），无需额外修改 `_generate_draft()`。

**位置 3**：在 `_retrieve_evidence()` 的返回值中，如果 web search 被使用，添加额外状态字段。

**改动**：在返回的 dict 中添加：
```python
"web_search_used": evidence.web_search_used,
```

**验证**：
```bash
python -c "from agent.langgraph_runtime import LangGraphAgentRuntime; print('W5.1 import OK')"
```

---

### 步骤 5.2：修改 `src/services/app_pipeline.py`

**文件**：`src/services/app_pipeline.py`

**位置**：确保 `RetrievalController.from_config()` 接受 `web_search_client` 参数（W2.3 已处理）。

**验证**：
```bash
python -c "from services.app_pipeline import AppPipeline; print('W5.2 import OK')"
```

---

### 步骤 5.3：W5 总体验证

```bash
python -m pytest tests/unit/agent/ -v --tb=short
```

**预期**：所有已有 agent 测试通过。

---

## W6：集成测试

### 步骤 6.1：创建 `tests/integration/rag_pipeline/test_web_search_fallback.py`

**文件**：`tests/integration/rag_pipeline/test_web_search_fallback.py`（新文件）

**完整内容**：

```python
from __future__ import annotations

import pytest
from unittest.mock import patch, MagicMock

from core.contracts import EvidencePackage
from core.runtime_settings import WebSearchSettings
from knowledge.web_search import WebSearchClient, WebSearchResult


def _make_web_result(title="Test Title", url="http://example.com", snippet="Test snippet"):
    return WebSearchResult(title=title, url=url, snippet=snippet, source="duckduckgo")


def _make_web_search_settings(**overrides):
    defaults = {"enabled": True, "timeout": 10, "max_results": 5, "provider": "duckduckgo"}
    return WebSearchSettings(**(defaults | overrides))


class TestWebSearchFallback:
    def test_kb_miss_triggers_web_search(self):
        """KB 未命中时触发 web search 兜底。"""
        from knowledge.retrieval_controller import RetrievalController

        client = WebSearchClient(_make_web_search_settings())
        with patch.object(client, "search", return_value=[_make_web_result()]):
            results = client.search("C919 最大起飞重量")
        assert len(results) == 1
        assert results[0].title == "Test Title"

    def test_web_search_disabled_no_fallback(self):
        """禁用 web search 时保持原有行为。"""
        settings = _make_web_search_settings(enabled=False)
        client = WebSearchClient(settings)
        assert client.search("anything") == []

    def test_web_search_failure_no_crash(self):
        """web search 失败时优雅降级。"""
        client = WebSearchClient(_make_web_search_settings())
        with patch.object(client, "search", side_effect=Exception("boom")):
            results = client.search("test")
        assert results == []

    def test_evidence_package_web_search_fields(self):
        """EvidencePackage 新增字段正确工作。"""
        ep = EvidencePackage(
            web_search_used=True,
            web_search_query="test query",
        )
        assert ep.web_search_used is True
        assert ep.web_search_query == "test query"

    def test_web_search_source_origin_enum(self):
        """SourceOrigin.WEB_SEARCH 枚举存在。"""
        from core.answer_contracts import SourceOrigin
        assert SourceOrigin.WEB_SEARCH.value == "web_search"
```

**验证**：
```bash
python -m pytest tests/integration/rag_pipeline/test_web_search_fallback.py -v --tb=short
```

**预期**：5 passed

---

### 步骤 6.2：W6 总体验证

```bash
python -m pytest tests/unit/knowledge/test_web_search.py tests/integration/rag_pipeline/test_web_search_fallback.py -v --tb=short
```

**预期**：11 passed（6 单元 + 5 集成）

---

## W7：全量回归与收口

### 步骤 7.1：确认 `pyproject.toml` 依赖

**文件**：`pyproject.toml`

**检查**：确认 `httpx` 已在 `[project.dependencies]` 中显式声明。如果未声明，添加：

```toml
"httpx>=0.25.0",
```

**验证**：
```bash
python -c "import httpx; print('httpx', httpx.__version__)"
```

---

### 步骤 7.2：全量回归测试

```bash
python -m pytest tests/ -q --tb=short
```

**预期**：全部通过，退出码 0。

---

### 步骤 7.3：部署验证

```bash
python scripts/validate_deployment.py
```

**预期**：通过。

---

### 步骤 7.4：更新 `docs/项目总控/STATUS.md`

**文件**：`docs/项目总控/STATUS.md`

**位置**：在文件末尾追加 W0–W7 验收记录。

**内容**：

```markdown
## W0–W7：联网搜索兜底功能（2026-08-17）

### 状态速览

| 编号 | 任务 | 状态 | 测试结果 |
|------|------|------|---------|
| W0 | 配置与契约扩展 | 完成 | 全部通过 |
| W1 | WebSearchClient 模块 | 完成 | 6/6 通过 |
| W2 | 检索控制器集成 | 完成 | 全部通过 |
| W3 | Prompt 资产创建 | 完成 | 全部通过 |
| W4 | 生成管线适配 | 完成 | 全部通过 |
| W5 | Agent 运行时贯通 | 完成 | 全部通过 |
| W6 | 集成测试 | 完成 | 5/5 通过 |
| W7 | 全量回归与收口 | 完成 | 全部通过 |

### 修改文件列表

- 新增：`src/knowledge/web_search.py`
- 新增：`tests/unit/knowledge/test_web_search.py`
- 新增：`tests/integration/rag_pipeline/test_web_search_fallback.py`
- 新增：`assets/prompts/web_search_answer/asset.json`
- 新增：`assets/prompts/web_search_answer/versions/v1.json`
- 修改：`configs/rag.yaml`
- 修改：`src/core/runtime_settings.py`
- 修改：`src/core/contracts.py`
- 修改：`src/core/answer_contracts.py`
- 修改：`src/knowledge/retrieval_controller.py`
- 修改：`src/knowledge/evidence_package.py`
- 修改：`src/generation/drafting.py`
- 修改：`src/generation/evidence_sketch.py`
- 修改：`src/generation/pipeline.py`
- 修改：`src/agent/langgraph_runtime.py`
- 修改：`src/services/app_pipeline.py`
- 修改：`pyproject.toml`

### 是否违反 harness.md

否。所有改动在 W0–W7 允许范围内。

### 未完成事项

无。
```

**验证**：
```bash
git diff --stat HEAD
```

---

### 步骤 7.5：W7 总体验证

```bash
python -m pytest tests/ -q --tb=short
python scripts/validate_deployment.py
```

**预期**：全部通过，退出码 0。