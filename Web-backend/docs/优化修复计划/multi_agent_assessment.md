# 多 Agent 协作可行性评估

> 任务 T18 · 2026-07-26
> 对应 `task.md §T18`，`spec.md §T18`，`harness.md §T18`

---

## 概述

评估将当前单体 `LangGraphAgentRuntime` 中的检索（Retrieval）、生成（Generation）、自查（Self-Check）拆分为独立子 Agent（Supervisor-Worker 模式）的可行性、收益与风险。

当前架构（T09 拆分后）已有 8 个独立节点类和一个 `NodeRegistry`，为多 Agent 拆分提供了基础。但节点之间仍在同一个 LangGraph 状态机内运行，共享进程内存和 `EphemeralRunArtifacts`。

---

## 拆分方案

### Worker Agent 分配

| Worker | 职责 | 当前节点 |
|--------|------|----------|
| **Retrieval Agent** | 查询改写 → 多通道检索 → 证据融合 → 重排 | `context_resolution` → `retrieve_evidence` → `retrieve_more` |
| **Generation Agent** | 记忆上下文 → 计划 → 大纲 → 草稿 → 引用绑定 | `generate_draft` |
| **Self-Check Agent** | 安全检查 → 决策路由 → 失败恢复 | `self_check` → `rewrite` |
| **Supervisor** | 上下文解析 → 路由选择 → 最终回复 | `supervisor_route` → `finalize` |

### 通信方式

- **Checkpoint 总线**：共享 SQLite checkpoint 数据库作为状态持久化层
- **显式合同**：每个 Worker 输入/输出定义为 Pydantic/TypedDict 合同
- **结果汇总**：Supervisor 收集各 Worker 的 evidence/answer/report 数据

---

## 利弊分析

### 优势

1. **独立部署与扩展**：检索 Worker 可独立扩容；生成 Worker 可运行不同模型
2. **故障隔离**：生成 Worker 崩溃不丢失检索结果（已持久化）
3. **异步流水线**：Retrieval Agent 可在 Generation Agent 运行时预取下一轮证据
4. **关注点分离**：每个 Worker 可独立测试、独立降级

### 劣势

1. **序列化开销**：GraphState 需要序列化/反序列化跨 Worker 传输，增加延迟
2. **状态一致性**：多 Worker 共享 checkpoint 需要分布式锁或乐观并发控制
3. **监控复杂度**：需要跨进程/跨服务追踪（distributed tracing）
4. **部署复杂度**：从单体进程变为多服务部署，需要容器编排、服务发现

---

## 业务必要性

| 维度 | 评估 |
|------|------|
| 当前性能瓶颈 | 主要在 LLM 调用（占 >80% 延迟），非状态机调度 |
| 并发需求 | 当前单进程可处理 10+ 并发请求（WAL + per-run 锁已支持） |
| 模型多样性 | 检索/生成/自查均可使用同一模型（当前架构支持） |
| 团队协作 | 代码拆分（T09/T10）已解决模块边界，无需多服务 |

**结论**：当前业务阶段**不建议**实施 Supervisor-Worker 多 Agent 拆分。

---

## 实施成本估算

| 阶段 | 工作量 | 说明 |
|------|--------|------|
| 协议定义 | 2-3 天 | Worker 间通信合同（protobuf / JSON schema） |
| Worker 提取 | 3-5 天 | 将现有节点逻辑包装为独立服务 |
| Supervisor 实现 | 2-3 天 | 路由/协调/超时/降级逻辑 |
| 状态迁移 | 2-3 天 | checkpoint 兼容性 + 灰度切换 |
| 测试与验证 | 3-5 天 | 等价性测试 + 压力测试 |
| **总计** | **12-19 天** | 约 2-3 周（单人） |

---

## 对现有契约的影响

- `TextQueryRequest` / `TextQueryResponse`：不受影响（Supervisor 仍暴露相同接口）
- `EvidencePackage` / `AnswerEnvelope` / `CheckReport`：需要序列化支持
- `checkpoint` 格式：需要添加 Worker 版本字段
- `RunTrace`：需要添加分布式 span 支持

---

## 建议

**不建议**在当前阶段实施多 Agent 拆分。理由：

1. **业务必要性不足**：当前性能瓶颈在 LLM 推理，不在 Agent 调度
2. **单进程架构足够**：T02（per-run 锁）+ T13（WAL）已解决并发瓶颈
3. **实施成本高**：12-19 天开发工期，可做更多业务功能
4. **引入新风险**：分布式系统复杂性（网络分区、超时、状态一致性）

### 何时重新考虑

- LLM 推理延迟降低到 <500ms，调度成为主要瓶颈时
- 需要为不同任务使用不同模型提供商时
- 团队人数增长到需要独立部署模块时
