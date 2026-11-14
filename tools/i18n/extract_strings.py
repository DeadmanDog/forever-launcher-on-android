#!/usr/bin/env python3
"""Collect the app's English/Hungarian UI text pairs for the extra UI languages.

Sources: tx("en", "hu") and t("en", "hu") calls, `"en" to "hu"` label pairs (addon jobs) and the few
`if (hungarian) "hu" else "en"` branches. Kotlin string templates ($name, ${expr}) become numbered placeholders
{0}, {1} ... in order of appearance; the app matches the rendered English text against these templates at run time
(WowI18n) and fills the translated template with the same values.

    python3 -B tools/i18n/extract_strings.py            # writes tools/i18n/source_en.json
    python3 -B tools/i18n/extract_strings.py --check    # fails when a language file misses a source string
"""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java"
OUT = ROOT / "tools/i18n/source_en.json"
ASSETS = ROOT / "app/src/main/assets/i18n"
LANGS = ["de", "fr", "es", "pt-BR", "ru", "ko", "zh-CN", "zh-TW"]
HU_CHARS = re.compile("[áéíóöőúüűÁÉÍÓÖŐÚÜŰ]")


def parse_literal(text: str, i: int):
    """Parses the Kotlin string literal starting at text[i] == '"'. Returns (template, end index) or None."""
    if text.startswith('"""', i):
        return None
    assert text[i] == '"'
    i += 1
    out, n, exprs = [], 0, []
    while i < len(text):
        c = text[i]
        if c == '"':
            return "".join(out), i + 1, exprs
        if c == "\n":
            return None
        if c == "\\":
            e = text[i + 1]
            if e == "u":
                out.append(chr(int(text[i + 2:i + 6], 16))); i += 6; continue
            out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"', "$": "$", "'": "'", "r": "\r", "b": "\b"}.get(e, e)); i += 2; continue
        if c == "$" and i + 1 < len(text) and text[i + 1] == "{":
            depth, j = 1, i + 2
            while depth:
                ch = text[j]
                if ch == '"':
                    lit = parse_literal(text, j)
                    if lit is None:
                        return None
                    j = lit[1]; continue
                depth += {"{": 1, "}": -1}.get(ch, 0); j += 1
            exprs.append(" ".join(text[i + 2:j - 1].split()))
            out.append("{%d}" % n); n += 1; i = j; continue
        if c == "$" and i + 1 < len(text) and (text[i + 1].isalpha() or text[i + 1] == "_"):
            j = i + 1
            while j < len(text) and (text[j].isalnum() or text[j] == "_"):
                j += 1
            exprs.append(text[i + 1:j])
            out.append("{%d}" % n); n += 1; i = j; continue
        if c in "{}":
            # A literal brace would read as a placeholder; double it like MessageFormat.
            out.append(c * 2); i += 1; continue
        out.append(c); i += 1
    return None


def skip_ws(text, i):
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def remap(en, hu):
    """The Hungarian template with its placeholders numbered like the English one (matched by the template expression)."""
    _, _, en_exprs = en
    template, _, hu_exprs = hu
    def repl(m):
        k = int(m.group(1))
        expr = hu_exprs[k] if k < len(hu_exprs) else None
        return "{%d}" % en_exprs.index(expr) if expr in en_exprs else m.group(0)
    return re.sub(r"(?<!\{)\{(\d+)\}(?!\})", repl, template)


def pairs_in(text: str):
    found = []
    for m in re.finditer(r'(?<![A-Za-z0-9_])(tx|t|tr|AddonLimitException)\(', text):
        i = skip_ws(text, m.end())
        if i >= len(text) or text[i] != '"':
            continue
        a = parse_literal(text, i)
        if not a:
            continue
        i = skip_ws(text, a[1])
        if text[i] != ",":
            continue
        i = skip_ws(text, i + 1)
        if text[i] != '"':
            continue
        b = parse_literal(text, i)
        if b:
            found.append((a[0], remap(a, b)))
    # WowI18n.pick(context, "en", "hu")
    for m in re.finditer(r'WowI18n\.pick\(\s*[\w.]+\s*,\s*"', text):
        a = parse_literal(text, m.end() - 1)
        if not a:
            continue
        i = skip_ws(text, a[1])
        if text[i] != ",":
            continue
        i = skip_ws(text, i + 1)
        if text[i] == '"':
            b = parse_literal(text, i)
            if b:
                found.append((a[0], remap(a, b)))
    # "en" to "hu" label pairs: only when the right side is Hungarian text.
    for m in re.finditer(r'(?<![\\A-Za-z0-9_])"(?=[^"\n]*"\s+to\s+")', text):
        a = parse_literal(text, m.start())
        if not a:
            continue
        i = skip_ws(text, a[1])
        if not text.startswith("to", i):
            continue
        i = skip_ws(text, i + 2)
        if i < len(text) and text[i] == '"':
            b = parse_literal(text, i)
            if b and HU_CHARS.search(b[0]) and a[0] != b[0]:
                found.append((a[0], remap(a, b)))
    # if (hungarian) "hu" else "en"
    for m in re.finditer(r'if \(hungarian\)\s*"', text):
        hu = parse_literal(text, m.end() - 1)
        if not hu:
            continue
        i = skip_ws(text, hu[1])
        if not text.startswith("else", i):
            continue
        i = skip_ws(text, i + 4)
        if i < len(text) and text[i] == '"':
            en = parse_literal(text, i)
            if en:
                found.append((en[0], remap(en, hu)))
    return found


def main():
    entries = {}
    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text()
        for en, hu in pairs_in(text):
            if not en.strip() or re.fullmatch(r"(\{\d+\}|\s|[\W\d])*", en):
                continue
            entries.setdefault(en, {"hu": hu, "files": set()})["files"].add(path.name)
    data = {en: {"hu": v["hu"], "files": sorted(v["files"])} for en, v in sorted(entries.items())}
    # The release tree (tools/prepare_forever_release.py) relabels the pacing defaults; its texts need translations too.
    # 1.3.2: the pacing choice has one app mode, so only the FIFO latency relabels remain.
    release = [("Only with FIFO. Smooth (default): frames", "Only with FIFO. Smooth: frames"),
               ("Lowest (experimental): also tries", "Lowest (default): also tries"),
               ("Smooth (default)", "Smooth"), ("Lowest (experimental)", "Lowest (default)")]
    for en in list(data):
        variant = en
        for old, new in release:
            variant = variant.replace(old, new)
        if variant != en and variant not in data:
            data[variant] = {"hu": "", "files": ["release"]}
    if "--check" in sys.argv:
        bad = 0
        for lang in LANGS:
            f = ASSETS / f"{lang}.json"
            have = json.loads(f.read_text()) if f.exists() else {}
            missing = [en for en in data if en not in have]
            if missing:
                bad += 1
                print(f"{lang}: {len(missing)} missing, e.g. {missing[:3]}")
        sys.exit(1 if bad else 0)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    # Hungarian table for texts that background work reports in English (the downloader's progress); tx() itself picks hu.
    hu = {en: v["hu"] for en, v in data.items() if v["hu"] and v["hu"] != en}
    (ASSETS / "hu.json").write_text(json.dumps(hu, ensure_ascii=False, indent=0) + "\n")
    print(f"{len(data)} strings, {sum(len(k) for k in data)} chars -> {OUT}")


if __name__ == "__main__":
    main()
