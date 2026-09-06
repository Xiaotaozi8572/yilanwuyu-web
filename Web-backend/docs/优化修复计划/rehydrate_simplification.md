# 双轨制恢复简化评估

> 任务 T19 · 2026-07-26
> 对应 `task.md §T19`，`spec.md §T19`，`harness.md §T19`

---

## 背景

`LangGraphAgentRuntime` 维护两条并行状态恢复路径（"双轨制"）：

1. **LangGraph Checkpoint**（持久化）— SQLite 中的最小脱敏状态（路由、计划、指纹）
2. **EphemeralRunArtifacts**（进程内）— 内存中的完整类型化领域对象

`_rehydrate_*` 方法在进程内 artifact 不可用时从 checkpoint 重建领域对象。这引入了两套相互兼容的逻辑，以及 `_required_*` / `_fail_closed_*` 等复杂的恢复担保。

---

## 方案对比

### 方案 A：Checkpoint 作为单一权威源

**做法**：消除 `EphemeralRunArtifacts`，始终从 checkpoint 重建领域对象。

| 维度 | 评价 |
|------|------|
| 一致性 | ✅ 强一致，无双源分歧风险 |
| 复杂度 | ❌ 当前 checkpoint 状态太精简，重建需要完整序列化所有领域对象 |
| 性能 | ❌ 每次重建都需要解析 JSON + 反序列化，增加延迟 |
| 变更影响 | ❌ 需要修改 GraphState 结构以包含更多重建字段 |

### 方案 B：显式状态版本号

**做法**：保留双轨，但为每个 artifact 添加版本指纹，用版本号检测分歧。

| 维度 | 评价 |
|------|------|
| 一致性 | ✅ 版本号校验可检测双源是否同步 |
| 复杂度 | ✅ 增量改动，无需重新架构 |
| 性能 | ✅ 无额外序列化，仅指纹比较 |
| 变更影响 | ✅ 仅影响 `ArtifactRehydrator` 类 |

---

## 建议

建议采用 **方案 B**（显式状态版本号）。理由：

1. **增量风险低**：不改变 checkpoint 结构，不改 GraphState
2. **兼容现有逻辑**：`_rehydrate_*` 方法结构不变，仅添加版本校验
3. **最小化代码改动**：只需在 `ArtifactRehydrator` 中添加版本号生成器和校验逻辑

### 实施概要

1. 在 `ArtifactRehydrator` 中添加 `_artifact_version(fingerprint: str) -> str` 方法
2. 每个 `_rehydrate_*` 方法在返回对象前附加版本号
3. 存入 checkpoint 时记录版本号
4. 读取时比较版本号，一致则直接使用 in-memory artifact，不一致则重建
