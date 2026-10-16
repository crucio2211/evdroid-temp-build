#!/usr/bin/env python3
"""Thin the x86_64 slice out of a Mach-O universal binary."""
import struct
import sys

src, arch, dst = sys.argv[1], sys.argv[2], sys.argv[3]
WANT = {"x86_64": 16777223, "arm64": 16777228}[arch]
b = open(src, "rb").read()
magic, nfat = struct.unpack(">II", b[:8])
assert magic == 0xCAFEBABE, hex(magic)
for i in range(nfat):
    cput, _cpusub, off, size, _align = struct.unpack(">5I", b[8 + 20 * i:28 + 20 * i])
    print("arch", i, "cputype", cput, "offset", off, "size", size)
    if cput == WANT:
        open(dst, "wb").write(b[off:off + size])
        print("wrote", arch, "slice", size, "bytes to", dst)
        return_code = 0
        break
else:
    print("arch", arch, "not found", file=sys.stderr)
    return_code = 1
sys.exit(return_code)
