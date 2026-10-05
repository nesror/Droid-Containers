#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check Google Play listing copy: field lengths + policy red flags.

Usage:
    python tool/check_listing_lengths.py            # scans play-store/listing/*/listing.md
    python tool/check_listing_lengths.py <file>...

For every `## <label> (<count> / <limit>)` header it verifies that:

1. the actual length of the following code block matches the annotated <count>
   (Play counts Unicode code points, and Google validates What's new only at
   commit-edit time, when the bundle has already been uploaded);
2. the block does not exceed <limit>;
3. policy red flags are absent: appended keyword lists, keyword stuffing.

Exit code is non-zero when anything fails, so CI can gate a release on it.
"""

import glob
import os
import re
import sys

HEADER = re.compile(r"^##\s+(?P<label>.+?)\s*\((?P<count>\d+)\s*/\s*(?P<limit>\d+)\)\s*$")

# Terms that triggered (or would trigger) a Play "Metadata policy" rejection.
BANNED = [
    "keywords:", "keywords：", "关键词:", "关键词：",
    "podman", "terminus", "termux", "self-hosting", "自托管",
]

TARGET_CHARS = {"app name": 30, "short description": 80, "full description": 4000,
                "what's new": 500, "应用名称": 30, "简短说明": 80, "完整说明": 4000,
                "更新说明": 500}


def check(path):
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().split("\n")

    errors, checked = [], 0
    i = 0
    while i < len(lines):
        m = HEADER.match(lines[i])
        if not m:
            i += 1
            continue
        label, annotated, limit = m.group("label"), int(m.group("count")), int(m.group("limit"))

        # find the code block that follows
        while i < len(lines) and lines[i].strip() != "```":
            i += 1
        i += 1
        body = []
        while i < len(lines) and lines[i].strip() != "```":
            body.append(lines[i])
            i += 1
        i += 1
        text = "\n".join(body)
        checked += 1

        if len(text) != annotated:
            errors.append(f"{label}: annotated {annotated} but block is {len(text)} chars")
        if len(text) > limit:
            errors.append(f"{label}: {len(text)} chars exceeds the {limit} limit")

        expect = TARGET_CHARS.get(label.strip().lower())
        if expect and expect != limit:
            errors.append(f"{label}: header limit {limit} should be {expect}")

        low = text.lower()
        for bad in BANNED:
            if bad in low:
                errors.append(f"{label}: contains banned term {bad!r}")

    if not checked:
        errors.append("no '## <label> (n / limit)' headers found")
    if "\ufffd" in "".join(lines):
        errors.append("contains U+FFFD replacement characters (encoding damage)")
    return checked, errors


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    targets = argv[1:] or sorted(glob.glob(os.path.join(root, "play-store", "listing", "*", "listing.md")))
    if not targets:
        print("no listing files found", file=sys.stderr)
        return 1

    failed = False
    for path in targets:
        checked, errors = check(path)
        rel = os.path.relpath(path, root)
        if errors:
            failed = True
            print(f"FAIL {rel} ({checked} fields)")
            for err in errors:
                print(f"     - {err}")
        else:
            print(f"OK   {rel} ({checked} fields within limits, no red flags)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
