# 记忆架构通过全链路追踪、消融评测和故障注入验收

Python、gRPC、Redis Streams、Java、PostgreSQL 与 Neo4j 使用统一脱敏追踪标识，并以 OpenTelemetry、Prometheus 和 Grafana 展示延迟、三态结果、缓存命中、事件积压、投影延迟、候选治理及遗忘时效。验收包含中文航空跨会话评测、无记忆/仅向量/九层混合消融，以及 Java、Redis 故障和事件恢复演示；个性化收益必须与航空事实正确性分别评分，记忆不得通过改变事实结论提升个性化指标。
