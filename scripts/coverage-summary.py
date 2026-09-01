#!/usr/bin/env python
"""P0(2.1): 汇总各模块 JaCoCo 报告 → 全项目/分包覆盖率（mvn -B verify 后运行）。

Usage: python scripts/coverage-summary.py [--write docs/coverage-baseline.md]
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = ["aether-api", "aether-types", "aether-domain", "aether-infrastructure", "aether-trigger", "aether-app"]


def read_counters(xml_path: Path):
    """返回 {counter_type: (missed, covered)}；文件缺失返回 {}。"""
    if not xml_path.exists():
        return {}
    root = ET.parse(xml_path).getroot()
    out = {}
    for c in root.findall("counter"):
        t = c.get("type")
        m, cov = int(c.get("missed")), int(c.get("covered"))
        if t in out:
            pm, pc = out[t]
            out[t] = (pm + m, pc + cov)
        else:
            out[t] = (m, cov)
    return out


def pct(cov: int, total: int) -> str:
    return f"{100 * cov / total:.1f}%" if total else "n/a"


def main():
    lines = []
    total = {}
    print(f"{'module':<22}{'LINE':>9}{'BRANCH':>9}{'INSTR':>9}")
    lines.append("| 模块 | 行覆盖率 | 分支覆盖率 | 指令覆盖率 |")
    lines.append("|------|---------:|-----------:|-----------:|")
    for mod in MODULES:
        c = read_counters(ROOT / mod / "target/site/jacoco/jacoco.xml")
        if not c:
            print(f"{mod:<22}{'(no report)':>9}")
            continue
        line = c.get("LINE", (0, 0))
        branch = c.get("BRANCH", (0, 0))
        instr = c.get("INSTRUCTION", (0, 0))
        print(f"{mod:<22}{pct(line[1], sum(line)):>9}{pct(branch[1], sum(branch)):>9}{pct(instr[1], sum(instr)):>9}")
        lines.append(f"| {mod} | {pct(line[1], sum(line))} | {pct(branch[1], sum(branch))} | {pct(instr[1], sum(instr))} |")
        for t, v in c.items():
            if t in total:
                total[t] = (total[t][0] + v[0], total[t][1] + v[1])
            else:
                total[t] = v

    line = total.get("LINE", (0, 0))
    branch = total.get("BRANCH", (0, 0))
    print(f"{'TOTAL':<22}{pct(line[1], sum(line)):>9}{pct(branch[1], sum(branch)):>9}")
    lines.append(f"| **全项目** | **{pct(line[1], sum(line))}** | **{pct(branch[1], sum(branch))}** | |")

    # domain 核心包分布（盲区定位）
    xml = ROOT / "aether-domain/target/site/jacoco/jacoco.xml"
    if xml.exists():
        print("\ndomain 包级行覆盖率（升序前 12 = 盲区）：")
        lines.append("\n### domain 包级行覆盖率（盲区排序）\n")
        lines.append("| 包 | 行覆盖率 |")
        lines.append("|----|---------:|")
        pkgs = []
        for p in ET.parse(xml).getroot().iter("package"):
            lc = [c for c in p.findall("counter") if c.get("type") == "LINE"]
            if lc:
                m, cov = int(lc[0].get("missed")), int(lc[0].get("covered"))
                if m + cov:
                    pkgs.append((100 * cov / (m + cov), p.get("name").replace("/", "."), cov, m + cov))
        for pctv, name, cov, tot in sorted(pkgs)[:12]:
            print(f"  {name:<70}{pctv:6.1f}%  ({cov}/{tot})")
            lines.append(f"| {name} | {pctv:.1f}% |")

    if "--write" in sys.argv:
        out = ROOT / "docs/coverage-baseline.md"
        head = [
            "# 覆盖率基线（P0 2.1）",
            "",
            f"> 生成命令：`mvn -B verify && python scripts/coverage-summary.py --write docs/coverage-baseline.md`",
            "",
        ]
        out.write_text("\n".join(head + lines) + "\n", encoding="utf-8")
        print(f"\nwritten -> {out}")


if __name__ == "__main__":
    main()
