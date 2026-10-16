#!/usr/bin/env python3
"""Name the rule in every store-trace line (experiment scaffolding)."""
import re
import sys

p = "src/delta/delta_rules.c"
s = open(p).read()
pat = re.compile(
    r'fprintf\(stderr, "# store %d at %08x = %08x\\n", w,'
    r"\s*\(unsigned\)\(size_t\)at, \(unsigned\)v\);"
)
rep = (
    r'fprintf(stderr, "# store %d %s = %08x\n", w, '
    r'delta_rule_here ? delta_rule_here->name : "?", (unsigned)v);'
)
s2, n = pat.subn(lambda m: rep, s)
assert n == 1, "pattern count %d" % n
open(p, "w").write(s2)
print("store trace now names the rule")
