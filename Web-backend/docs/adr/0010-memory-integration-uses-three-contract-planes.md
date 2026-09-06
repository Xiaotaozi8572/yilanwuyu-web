# Python 与 Java 记忆集成采用三平面契约

当前轮记忆上下文通过 gRPC 与 Protobuf 的 `ResolveMemoryContext` 在 150 毫秒截止时间内读取；Python 本地投递箱通过 gRPC `SubmitMemoryEvents` 提交 CloudEvents 语义的版本化事件，Java 持久化确认后再在内部发布到 Redis Streams；授权、查看、纠正、遗忘、审计和健康检查使用 OpenAPI 描述的 REST 管理接口。契约演进只允许向后兼容地增加字段，禁止复用 Protobuf 字段编号，事件类型显式版本化，并由 Python、Java 双端契约测试共同验证。
