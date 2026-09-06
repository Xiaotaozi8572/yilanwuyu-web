"""一次性数据修复：C919 含小翼翼展 35.4 -> 35.8（权威值），并连带修正派生数字。

权威值来源：新华社/中国商飞/中国政府贺电/快懂百科/Simple Flying 多源一致，
C919 翼展（含翼尖小翼）= 35.8 m，净翼展 = 33.6 m。35.4 是 Wikipedia 旧数据（后被修正）。
因此派生量同步修正：每侧小翼翼展贡献 0.9 -> 1.1 m，两侧合计 1.8 -> 2.2 m。

注意：winglet-aerodynamics 的"小翼高度 0.9 m"是独立几何量（高度），非翼展贡献，保留不动。
"""
from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
C919_ROOT = PROJECT_ROOT / "data" / "knowledge_sources" / "c919"

# 精确派生数字替换：old_string -> new_string，均为"35.4 已全局替换为 35.8"之后的文本。
# 每项附带期望命中次数，便于校验。
DERIVED_REPLACEMENTS: list[tuple[str, str, str, int]] = [
    # (相对路径, old, new, 期望命中次数)
    # 合计 1.8 -> 2.2
    ("01_science/01_overview/c919-design-philosophy-20260728-candidate.md",
     "增加约 1.8 m", "增加约 2.2 m", 1),
    ("01_science/02_structure_systems/c919-wing-structure-details-20260728-candidate.md",
     "| 小翼贡献长度 | 1.8 m | 每侧 0.9 m |",
     "| 小翼贡献长度 | 2.2 m | 每侧 1.1 m |", 1),
    ("01_science/03_principles/c919-drag-breakdown-20260728-candidate.md",
     "增加 1.8 m。", "增加 2.2 m。", 1),
    ("01_science/03_principles/c919-winglet-aerodynamics-20260728-candidate.md",
     "| 总翼展增加 | 1.8 m | 两侧合计 |",
     "| 总翼展增加 | 2.2 m | 两侧合计 |", 1),
    ("01_science/03_principles/c919-winglet-aerodynamics-20260728-candidate.md",
     "| C919 | 鲨鱼鳍小翼 | 35.8 m | 1.8 m |",
     "| C919 | 鲨鱼鳍小翼 | 35.8 m | 2.2 m |", 1),
    ("01_science/06_comparison/c919-vs-b737max-aerodynamic-comparison-20260728-candidate.md",
     "增加 1.8 m）", "增加 2.2 m）", 1),
    # 每侧 0.9 -> 1.1
    ("01_science/02_structure_systems/c919-wing-structure-details-20260728-candidate.md",
     "每侧 0.9 米，与外翼段", "每侧 1.1 米，与外翼段", 1),
    ("01_science/02_structure_systems/c919-wing-structure-details-20260728-candidate.md",
     "每侧增加 0.9 米翼展", "每侧增加 1.1 米翼展", 1),
    ("01_science/02_structure_systems/c919-wing-structure-details-20260728-candidate.md",
     "| 翼尖小翼长度 | 0.9 m/侧 |", "| 翼尖小翼长度 | 1.1 m/侧 |", 1),
    ("01_science/03_principles/c919-winglet-aerodynamics-20260728-candidate.md",
     "每侧小翼贡献约 0.9 米", "每侧小翼贡献约 1.1 米", 1),
]


def collect_files() -> list[Path]:
    files: list[Path] = []
    for ext in ("*.md", "*.yaml", "*.yml"):
        files.extend(C919_ROOT.rglob(ext))
    return sorted(files)


def global_replace(files: list[Path]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for f in files:
        text = f.read_text(encoding="utf-8")
        n = text.count("35.4")
        if n:
            text = text.replace("35.4", "35.8")
            f.write_text(text, encoding="utf-8")
            counts[str(f.relative_to(PROJECT_ROOT))] = n
    return counts


def derived_replace() -> list[str]:
    errors: list[str] = []
    for rel, old, new, expected in DERIVED_REPLACEMENTS:
        f = C919_ROOT / rel
        text = f.read_text(encoding="utf-8")
        n = text.count(old)
        if n != expected:
            errors.append(
                f"[WARN] {rel}: '{old}' 命中 {n} 次（期望 {expected}）"
            )
            continue
        text = text.replace(old, new)
        f.write_text(text, encoding="utf-8")
    return errors


def main() -> int:
    files = collect_files()
    print(f"扫描到 {len(files)} 个知识源文件")
    counts = global_replace(files)
    print(f"\n=== 全局替换 35.4 -> 35.8（{len(counts)} 个文件）===")
    total = 0
    for rel, n in counts.items():
        print(f"  {n:>2} 处  {rel}")
        total += n
    print(f"  合计 {total} 处")
    print("\n=== 派生数字精确替换 ===")
    errors = derived_replace()
    for e in errors:
        print(e)
    print("派生数字替换完成" if not errors else "派生数字替换存在警告，见上")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
