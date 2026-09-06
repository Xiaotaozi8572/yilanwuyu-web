using System;
using System.Collections.Generic;
using UnityEngine;

namespace AgentBridge
{
    /// <summary>
    /// 消息分发器 —— 按 action 命名空间路由事件到订阅者。
    ///
    /// 支持两种订阅：
    ///   1. 精确匹配：Subscribe("agent.thinking.stream", handler)
    ///   2. 通配匹配：Subscribe("agent.*", handler)  匹配所有 agent. 开头的动作
    ///
    /// 线程说明：Dispatch 必须在主线程调用（由 MainThreadDispatcher 保证）。
    /// </summary>
    public class MessageDispatcher
    {
        private const string Wildcard = "agent.*";

        private readonly Dictionary<string, List<Action<MessageEnvelope>>> _exact
            = new Dictionary<string, List<Action<MessageEnvelope>>>();

        private readonly List<Action<MessageEnvelope>> _wildcard
            = new List<Action<MessageEnvelope>>();

        public void Subscribe(string actionPattern, Action<MessageEnvelope> handler)
        {
            if (actionPattern == Wildcard)
            {
                if (!_wildcard.Contains(handler))
                    _wildcard.Add(handler);
                return;
            }

            if (!_exact.TryGetValue(actionPattern, out var list))
            {
                list = new List<Action<MessageEnvelope>>();
                _exact[actionPattern] = list;
            }
            if (!list.Contains(handler))
                list.Add(handler);
        }

        public void Unsubscribe(string actionPattern, Action<MessageEnvelope> handler)
        {
            if (actionPattern == Wildcard)
            {
                _wildcard.Remove(handler);
                return;
            }

            if (_exact.TryGetValue(actionPattern, out var list))
                list.Remove(handler);
        }

        /// <summary>派发一条事件消息（仅 Event 类型走这里）</summary>
        public void Dispatch(MessageEnvelope msg)
        {
            // 精确匹配
            if (_exact.TryGetValue(msg.action, out var list))
            {
                // 倒序遍历，允许 handler 内部反订阅自身
                for (int i = list.Count - 1; i >= 0; i--)
                {
                    try { list[i].Invoke(msg); }
                    catch (Exception e) { Debug.LogError($"[Dispatcher] {msg.action} 处理异常: {e}"); }
                }
            }

            // 通配匹配
            if (msg.action.StartsWith("agent."))
            {
                for (int i = _wildcard.Count - 1; i >= 0; i--)
                {
                    try { _wildcard[i].Invoke(msg); }
                    catch (Exception e) { Debug.LogError($"[Dispatcher] 通配 {msg.action} 异常: {e}"); }
                }
            }
        }
    }
}
