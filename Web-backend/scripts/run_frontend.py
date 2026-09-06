"""T9 语音前端静态服务。

- 仅用标准库（http.server），无第三方依赖、无构建工具。
- 在独立端口提供 <ROOT>/assets/voice/ 静态文件（index.html / pcm-processor.js）。
- 与语音 WS 服务（scripts/run_voice.py，随机端口）端口分离。
- 仅绑定 127.0.0.1（loopback）。
"""

from __future__ import annotations

import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
DOCROOT = ROOT / "assets" / "voice"
DEFAULT_PORT = 8000
HOST = "127.0.0.1"


class _VoiceAssetsHandler(SimpleHTTPRequestHandler):
    """为 assets/voice/ 提供静态文件；禁用缓存便于前端调试。"""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, directory=str(DOCROOT), **kwargs)

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:
        print(f"[frontend-http] {self.address_string()} {fmt % args}")


def main() -> int:
    parser = argparse.ArgumentParser(description="翼览无余 语音前端静态服务（仅本机 127.0.0.1）")
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"HTTP 端口（默认 {DEFAULT_PORT}）",
    )
    args = parser.parse_args()

    if isinstance(args.port, bool) or not 1 <= args.port <= 65535:
        print(f"[错误] 端口必须为 1-65535 的整数，收到: {args.port!r}", file=sys.stderr)
        return 2
    if not DOCROOT.is_dir():
        print(f"[错误] 静态目录不存在: {DOCROOT}", file=sys.stderr)
        return 2

    try:
        httpd = ThreadingHTTPServer((HOST, args.port), _VoiceAssetsHandler)
    except OSError as exc:
        print(f"[错误] 无法绑定 {HOST}:{args.port}（端口可能被占用）: {exc}", file=sys.stderr)
        return 2

    url = f"http://{HOST}:{args.port}/index.html"
    print("=" * 64)
    print("翼览无余 · 语音前端已启动")
    print(f"  访问地址: {url}")
    print(f"  静态目录: {DOCROOT}")
    print(
        "  提示: 语音服务端口由 scripts/run_voice.py 启动时打印，"
        "请在页面输入该端口（或通过 ?port= 参数指定）。"
    )
    print("  按 Ctrl+C 停止。")
    print("=" * 64)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n正在停止…")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
