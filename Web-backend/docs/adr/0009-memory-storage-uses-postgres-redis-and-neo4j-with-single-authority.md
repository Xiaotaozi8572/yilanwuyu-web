# 记忆存储采用 PostgreSQL 权威源、Redis 加速层和 Neo4j 图投影

PostgreSQL 及 pgvector 保存交互事件、长期记忆、治理状态、来源、授权、事务投递箱和向量，是唯一权威存储；Neo4j Community 保存可从 PostgreSQL 重建的时间关系图投影。Java 使用 Caffeine 作为 L1 缓存，Redis 作为 Java 边界内部的 L2 共享缓存和 Streams 异步事件流，Python 不持有 Redis 凭据；Redis 故障时读取回退到权威存储，尚未被 Java 确认的事件保留在 Python 本地可靠投递箱，任何缓存、流或图投影都不得成为第二真相源。
