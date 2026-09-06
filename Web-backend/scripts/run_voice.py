"""Standalone voice WebSocket server entry point (T8).

Wraps :func:`voice.websocket_server.serve_voice` with a fixed default
loopback address (127.0.0.1:8765) and prints the actual listening endpoint
and configuration snapshot so clients can connect. ``--host``/``--port`` are
configurable; binding a non-loopback host requires the explicit opt-in flag
because it exposes the server to the network. No WebSocket protocol logic is
reimplemented here.
"""

from __future__ import annotations

import argparse
import asyncio
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from voice.settings import VoiceSettings
from voice.websocket_server import serve_voice

CONFIG_PATH = PROJECT_ROOT / "configs" / "voice.yaml"

LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}


async def main() -> None:
    parser = argparse.ArgumentParser(description="翼览无余 语音 WebSocket 服务")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    settings = VoiceSettings.from_file(CONFIG_PATH)
    server = await serve_voice(
        host=args.host,
        port=args.port,
        settings=settings,
        allow_non_loopback=(args.host not in LOOPBACK_HOSTS),
    )
    if args.host not in LOOPBACK_HOSTS:
        print(
            f"[警告] 已允许非回环监听（{args.host}），请确认网络边界与防火墙"
        )
    try:
        port = int(server.sockets[0].getsockname()[1])
        print(f"WS 地址: ws://{args.host}:{port}")
        print(f"健康检查: http://{args.host}:{port}/health")
        print(f"配置快照 ID: {settings.snapshot_id}")
        sys.stdout.flush()
        await server.wait_closed()
    finally:
        server.close()


if __name__ == "__main__":
    asyncio.run(main())