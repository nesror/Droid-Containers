#!/usr/bin/env python3
"""控制台 i18n 守门：四语言键集一致、无缺键、占位符一致、引用的键都存在。

用法：python tool/check_console_i18n.py   （退出码非 0 = 有问题）
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HTML = ROOT / "core" / "server" / "src" / "main" / "resources" / "web" / "index.html"
LANGS = ["en", "zh", "ru", "ja"]
BASE = "en"


def main() -> int:
    text = HTML.read_text(encoding="utf-8")

    block = re.search(r"const I18N = \{(.*?)\n\};", text, re.S)
    if not block:
        print("FAIL: web/index.html 里找不到 I18N 对象", file=sys.stderr)
        return 1

    catalogs: dict[str, dict[str, str]] = {}
    for lang in LANGS:
        body = re.search(rf"\n{lang}:{{(.*?)(?:\n\}},|\n\}};|\n\}})", block.group(1), re.S)
        if not body:
            print(f"FAIL: 找不到 {lang} 文案目录", file=sys.stderr)
            return 1
        entries = re.findall(r'"([^"]+)":"((?:[^"\\]|\\.)*)"', body.group(1))
        catalogs[lang] = dict(entries)

    base_keys = set(catalogs[BASE])
    problems: list[str] = []

    # 1) 键集一致（en 为基准；其余语言允许暂缺吗？不允许——缺键会静默回退英文，漏译不易被发现）
    for lang in LANGS:
        keys = set(catalogs[lang])
        missing = sorted(base_keys - keys)
        extra = sorted(keys - base_keys)
        if lang != BASE and missing:
            problems.append(f"{lang} 缺 {len(missing)} 键: {missing[:6]}")
        if extra:
            problems.append(f"{lang} 多 {len(extra)} 键: {extra[:6]}")

    # 2) 占位符一致（{name} 这类插值必须在每个语言里都存在，否则运行时留占位符）
    def placeholders(value: str) -> set[str]:
        return set(re.findall(r"\{(\w+)\}", value))

    for key in sorted(base_keys):
        want = placeholders(catalogs[BASE][key])
        for lang in LANGS:
            got = placeholders(catalogs[lang].get(key, ""))
            if got != want:
                problems.append(f"占位符不一致 {lang}/{key}: {sorted(got)} != {sorted(want)}")

    # 3) 代码里引用的键必须存在（动态拼接的键如 status.RUNNING 由回退逻辑兜底，这里只查字面量）
    used = set(re.findall(r'\bT\("([^"]+)"', text))
    used |= set(re.findall(r'data-i18n(?:-ph)?="([^"]+)"', text))
    for key in sorted(used):
        if key not in base_keys:
            problems.append(f"代码引用了不存在的键: {key}")

    if problems:
        for p in problems:
            print("FAIL:", p, file=sys.stderr)
        print(f"\n共 {len(problems)} 个问题", file=sys.stderr)
        return 1

    print(f"OK  {HTML.name}: 四语言各 {len(base_keys)} 键，占位符与引用一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
