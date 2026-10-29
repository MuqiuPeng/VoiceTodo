#!/usr/bin/env python3
"""Builds parser/src/main/resources/.../pinyin.txt from Unicode's Unihan_Readings.txt.

    curl -LO https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    unzip Unihan.zip Unihan_Readings.txt
    python3 scripts/gen-pinyin.py Unihan_Readings.txt

Keeps the modern readings only (kMandarin, then kTGHZ2013), tones stripped and
ü written as v: the corrector compares sounds, and archaic readings from
kHanyuPinyin would make unrelated characters look alike.
"""
import re
import sys
import unicodedata

def plain(s):
    # Decompose first: "ǚ" is a single code point; only after NFD is it u + marks.
    s = unicodedata.normalize("NFD", s.lower()).replace("u\u0308", "v")
    return "".join(c for c in s if unicodedata.category(c) != "Mn")

readings = {}
for line in open(sys.argv[1], encoding="utf-8"):
    m = re.match(r"U\+([0-9A-F]+)\t(kMandarin|kTGHZ2013)\t(.+)", line.rstrip("\n"))
    if not m:
        continue
    cp = int(m[1], 16)
    if not (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF):
        continue
    sylls = [plain(x.split(":")[-1]) for x in m[3].split()]
    cur = readings.setdefault(chr(cp), [])
    for s in sylls:
        if s not in cur:
            # kMandarin is read first in the file order of each character only by
            # chance; put it first explicitly.
            cur.insert(0, s) if m[2] == "kMandarin" else cur.append(s)

out = "parser/src/main/resources/app/voicetodo/parser/pinyin.txt"
with open(out, "w", encoding="utf-8") as f:
    f.write("# From Unicode Unihan (kMandarin, kTGHZ2013). Unicode License v3.\n")
    for ch in sorted(readings):
        f.write(f"{ch} {','.join(readings[ch])}\n")
print(len(readings), "characters ->", out)
