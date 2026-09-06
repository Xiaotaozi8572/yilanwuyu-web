#!/usr/bin/env python3
"""密钥扫描脚本（T03）：提交前阻断密钥入库。

仅使用 Python 标准库，无第三方依赖。

用法：
    python scripts/check_secrets.py             # 扫描 git 暂存区（默认，适合 pre-commit）
    python scripts/check_secrets.py --all       # 扫描全仓文本文件（不含 .git / .venv* / 二进制）

退出码：
    0 = 无命中；1 = 命中（打印 文件:行号:内容 摘要）。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# 匹配规则
# ---------------------------------------------------------------------------
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    # DeepSeek / DashScope 等 OpenAI 兼容平台密钥（sk- 前缀，>=20 位）
    ("sk-key", re.compile(r"\bsk-[A-Za-z0-9._-]{20,}\b")),
    # NVIDIA NIM 密钥（nvapi- 前缀，>=20 位）
    ("nvapi-key", re.compile(r"\bnvapi-[A-Za-z0-9._-]{20,}\b")),
    # Authorization: Bearer <token> 内联明文
    ("bearer-token", re.compile(r"\bBearer\s+[A-Za-z0-9._-]{20,}\b")),
]

# 白名单：文档/示例中的占位符，命中即跳过（仍按行打印级联判断）。
WHITELIST: tuple[str, ...] = (
    "sk-your-key-here",
    "sk-xxx",
    "sk-xxxx",
    "sk-placeholder",
    "sk-example",
    "sk-<your-key>",
)

SKIP_DIRS: tuple[str, ...] = (".git", ".venv", ".venv-review", "node_modules", "target")
BINARY_CHUNK = bytes(range(256))


def _is_text(path: Path) -> bool:
    """粗判文本文件：前 8KB 不含 NUL 字节即视为文本。"""
    try:
        with path.open("rb") as fh:
            head = fh.read(8192)
    except OSError:
        return False
    return b"\x00" not in head


def _iter_git_staged_files() -> list[Path]:
    """返回 git 暂存区（--cached）文件路径列表。"""
    proc = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "-z"],
        capture_output=True,
        text=False,
    )
    if proc.returncode != 0:
        sys.stderr.write(
            "check_secrets: 无法读取 git 暂存区（当前目录不是 git 仓库？）\n"
        )
        sys.exit(2)
    names = [n for n in proc.stdout.split(b"\x00") if n]
    root = Path.cwd()
    return [root / n.decode("utf-8", errors="replace") for n in names]


def _iter_all_files(root: Path) -> list[Path]:
    """返回全仓文本文件路径列表（跳过 SKIP_DIRS 与二进制）。"""
    files: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if not _is_text(path):
            continue
        files.append(path)
    return files


def scan_file(path: Path) -> list[tuple[int, str, str]]:
    """扫描单个文件，返回 [(行号, 规则名, 行内容)]。"""
    hits: list[tuple[int, str, str]] = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return hits
    for lineno, line in enumerate(lines, start=1):
        if any(placeholder in line for placeholder in WHITELIST):
            continue
        for rule_name, pattern in PATTERNS:
            if pattern.search(line):
                hits.append((lineno, rule_name, line.strip()[:120]))
                break
    return hits


def main() -> int:
    root = Path.cwd()
    if "--all" in sys.argv:
        targets = _iter_all_files(root)
    else:
        targets = _iter_git_staged_files()

    total = 0
    for path in targets:
        if not path.exists():
            continue
        hits = scan_file(path)
        for lineno, rule_name, line in hits:
            print(f"{path}:{lineno}: [{rule_name}] {line}")
            total += 1

    if total:
        sys.stderr.write(f"check_secrets: 检出 {total} 处疑似密钥，请清理后重试。\n")
        return 1
    print("check_secrets: 未检出疑似密钥。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
