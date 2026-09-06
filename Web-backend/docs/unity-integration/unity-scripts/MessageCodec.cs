using System.Text;
using Newtonsoft.Json;

namespace AgentBridge
{
    /// <summary>
    /// 消息编解码 —— 默认 JSON 实现，协议层预留 MessagePack 切换位。
    /// 切换方法：把 Encode/Decode 换成 MessagePack-CSharp 即可，上层无感知。
    /// </summary>
    public static class MessageCodec
    {
        private static readonly JsonSerializerSettings Settings = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            ReferenceLoopHandling = ReferenceLoopHandling.Ignore,
            // 枚举序列化为字符串，便于后端调试与跨语言兼容
            Converters = { new Newtonsoft.Json.Converters.StringEnumConverter() }
        };

        /// <summary>编码为 JSON 字符串（WebSocket 文本帧 / HTTP body 通用）</summary>
        public static string Encode(MessageEnvelope msg)
        {
            return JsonConvert.SerializeObject(msg, Settings);
        }

        /// <summary>从 JSON 字符串解码</summary>
        public static MessageEnvelope Decode(string raw)
        {
            return JsonConvert.DeserializeObject<MessageEnvelope>(raw, Settings);
        }

        /// <summary>编码为 UTF8 字节（WebSocket 二进制帧）</summary>
        public static byte[] EncodeBytes(MessageEnvelope msg)
        {
            return Encoding.UTF8.GetBytes(Encode(msg));
        }

        /// <summary>从字节解码</summary>
        public static MessageEnvelope DecodeBytes(byte[] bytes)
        {
            return Decode(Encoding.UTF8.GetString(bytes));
        }
    }
}
