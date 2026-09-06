using System;
using System.Collections.Generic;

namespace AgentBridge
{
    /// <summary>
    /// 消息类型枚举
    /// </summary>
    public enum MessageType
    {
        Request,    // Unity -> 后端 请求
        Response,   // 后端 -> Unity 对请求的响应
        Event,      // 后端 -> Unity 主动推送
        Error,      // 错误消息
        Heartbeat,  // 心跳
        Ack         // 确认收到
    }

    /// <summary>
    /// 统一消息信封 —— 所有 Unity 与 Agent 后端通信的根容器。
    /// 一条信封 = 一个完整业务消息，不区分传输通道（HTTP / WebSocket 共用）。
    /// </summary>
    [Serializable]
    public class MessageEnvelope
    {
        /// <summary>消息唯一 ID，用于关联请求-响应、断线重放去重</summary>
        public string msg_id;

        /// <summary>链路追踪 ID，跨 Agent 透传，便于后端日志关联</summary>
        public string trace_id;

        /// <summary>消息类型</summary>
        public MessageType msg_type;

        /// <summary>目标 / 来源 Agent 实例 ID</summary>
        public string agent_id;

        /// <summary>动作命名空间，点分格式，如 "agent.think" / "agent.action.start"</summary>
        public string action;

        /// <summary>协议版本</summary>
        public string version = "1.0";

        /// <summary>毫秒时间戳（UTC）</summary>
        public long timestamp;

        /// <summary>会话内序号，用于乱序检测</summary>
        public int seq;

        /// <summary>业务数据负载，结构由 action 决定</summary>
        public Dictionary<string, object> payload;

        /// <summary>元数据</summary>
        public MessageMeta meta;

        /// <summary>快捷构造请求</summary>
        public static MessageEnvelope CreateRequest(string agentId, string action,
            Dictionary<string, object> payload = null, int ttlMs = 5000)
        {
            return new MessageEnvelope
            {
                msg_id = Guid.NewGuid().ToString("N"),
                trace_id = Guid.NewGuid().ToString("N"),
                msg_type = MessageType.Request,
                agent_id = agentId,
                action = action,
                timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                payload = payload ?? new Dictionary<string, object>(),
                meta = new MessageMeta { ttl_ms = ttlMs }
            };
        }

        /// <summary>快捷构造事件</summary>
        public static MessageEnvelope CreateEvent(string agentId, string action,
            Dictionary<string, object> payload = null)
        {
            return new MessageEnvelope
            {
                msg_id = Guid.NewGuid().ToString("N"),
                msg_type = MessageType.Event,
                agent_id = agentId,
                action = action,
                timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                payload = payload ?? new Dictionary<string, object>()
            };
        }
    }

    [Serializable]
    public class MessageMeta
    {
        public string compress = "none";  // none | gzip | msgpack
        public int ttl_ms = 5000;         // 请求超时（毫秒）
        public bool require_ack = false;  // 是否要求 ACK（可靠投递）
    }
}
