using System;
using System.Threading.Tasks;
using NativeWebSocket;
using UnityEngine;

namespace AgentBridge
{
    /// <summary>
    /// WebSocket 客户端 —— 封装 NativeWebSocket。
    /// 跨平台：PC(Windows/Mac)、iOS、Android、WebGL 均支持。
    ///
    /// 依赖安装（Unity Package Manager）：
    ///   Add package from git URL -> https://github.com/endel/NativeWebSocket.git#upm
    ///
    /// 设计要点：
    ///   1. 收发均为异步，回调在主线程（NativeWebSocket 自带主线程派发）
    ///   2. 仅做传输，不含重连逻辑（由 ReconnectHandler 负责）
    ///   3. IsConnected 状态供上层判断
    /// </summary>
    public class WsClient
    {
        private WebSocket _ws;
        private readonly string _url;

        public bool IsConnected { get; private set; }

        /// <summary>收到文本消息（已 UTF8 解码）</summary>
        public event Action<string> OnMessage;

        /// <summary>连接成功</summary>
        public event Action OnConnected;

        /// <summary>连接断开（含主动关闭、异常、服务端关闭）</summary>
        public event Action OnDisconnected;

        public WsClient(string url)
        {
            _url = url;
        }

        public async Task ConnectAsync()
        {
            if (_ws != null && IsConnected) return;

            _ws = new WebSocket(_url);

            _ws.OnOpen += () =>
            {
                IsConnected = true;
                OnConnected?.Invoke();
            };

            _ws.OnMessage += bytes =>
            {
                var raw = System.Text.Encoding.UTF8.GetString(bytes);
                OnMessage?.Invoke(raw);
            };

            _ws.OnClose += code =>
            {
                IsConnected = false;
                OnDisconnected?.Invoke();
            };

            _ws.OnError += msg =>
            {
                Debug.LogError($"[WsClient] 错误: {msg}");
                IsConnected = false;
                OnDisconnected?.Invoke();
            };

            await _ws.Connect();
        }

        /// <summary>发送文本帧</summary>
        public bool Send(string raw)
        {
            if (!IsConnected || _ws == null) return false;
            _ws.SendText(raw);
            return true;
        }

        /// <summary>发送 ping（NativeWebSocket 底层自动处理，此方法用于显式心跳）</summary>
        public bool SendPing()
        {
            if (!IsConnected || _ws == null) return false;
            // 发送空字节触发底层 ping 帧
            _ws.Send(new byte[0]);
            return true;
        }

        /// <summary>必须在 Unity 主线程 Update 中调用，派发接收队列</summary>
        public void DispatchMessageQueue()
        {
#if !UNITY_WEBGL || UNITY_EDITOR
            _ws?.DispatchMessageQueue();
#endif
        }

        public async void Close()
        {
            if (_ws != null)
            {
                IsConnected = false;
                await _ws.Close();
            }
        }
    }
}
