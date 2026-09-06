# Java 记忆服务采用 JDK 21 与 Spring Boot 4

Java 服务使用 JDK 21 LTS、Spring Boot 4.0.x 与 Spring gRPC 1.0.3，并以 Maven Wrapper 和 BOM 固定兼容版本。PostgreSQL 双时间模型使用 Spring JDBC/JdbcClient 与 Flyway 的显式 SQL，Redis、Neo4j、安全和可观测性分别使用对应 Spring 模块，L1 缓存使用 Caffeine，测试使用 JUnit 5、Testcontainers、ArchUnit 与跨语言契约测试；不引入需要额外授权辨析的 jOOQ。
