#!/usr/bin/env python3
"""Split one-process store trace by sentence mark; list copyvar src,dst."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/stores-all.txt"
txt = open(path, errors="replace").read()
parts = re.split(r"(\[\.1he\][!?.])", txt)
cur = "start"
for p in parts:
    m = re.match(r"\[\.1he\]([!?.])", p)
    if m:
        cur = {"!": "bang", ".": "dot", "?": "query"}[m.group(1)]
        continue
    cvs = sorted(
        set(re.findall(r"copyvar\(@[0-9a-f]+, @([0-9a-f]+), @([0-9a-f]+)\)", p))
    )
    print(cur, len(cvs), "distinct src,dst pairs")
    for s, d in cvs[:30]:
        print(" ", cur, s, d)
