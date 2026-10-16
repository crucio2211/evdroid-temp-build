#!/usr/bin/env python3
"""Force nucl_high_tone's mark gate to fail (experiment scaffolding).

Replaces the gate's expected-kind load with an impossible immediate, so
the rule always backtracks instead of rendering high tone. Fails loudly
if the gate text is not found (line numbers drift as patches evolve).
"""
import re
import sys

p = "lang/enus/rules/u_integ.dr"
s = open(p).read()
m = re.search(
    r"(?s)(rule nucl_high_tone.*?load movw ind reg r6 3386 into r0w)", s
)
assert m, "gate not found in nucl_high_tone"
print("gate at offset", m.start(1))
s = (
    s[: m.start(1)]
    + m.group(1).replace(
        "load movw ind reg r6 3386 into r0w",
        "load movw imm 9999 into r0w",
        1,
    )
    + s[m.end(1) :]
)
open(p, "w").write(s)
print("gate forced")
