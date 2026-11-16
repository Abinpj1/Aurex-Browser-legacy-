#!/usr/bin/env python3
"""
Collects every user-visible string literal of the app into app/src/main/assets/ui_literals.txt.

The in-app language feature (Lang.kt) translates ONLY these strings (and nothing a page, a file name or a
person typed), so re-run this script after adding or changing UI text:

    python3 tools/gen_ui_literals.py

File format: one literal per line. "\\n" = newline, "\\\\" = backslash, and \\u0001 marks the place of a
${value} that is filled in at run time ("${n} threats blocked" -> "\\u0001 threats blocked").
"""
import os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "app/src/main/java/com/aurex/browser")
OUT = os.path.join(ROOT, "app/src/main/assets/ui_literals.txt")
PH = "\u0001"
ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}


def scan(src):
    """Yield every normal (non-raw) string literal as a list of parts [lit, None(=placeholder), lit...]."""
    i, n = 0, len(src)
    out = []

    def skip_template(i):
        # i is just after "${": skip to the matching "}" (strings inside are skipped too)
        depth = 1
        while i < n and depth:
            c = src[i]
            if c == '"':
                if src.startswith('"""', i):
                    j = src.find('"""', i + 3)
                    i = n if j < 0 else j + 3
                else:
                    _, i = read_string(i)
                continue
            if c == "'":
                i = skip_char(i)
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            i += 1
        return i

    def skip_char(i):
        # char literal: 'a' '\n' '\u0041' '"'
        j = i + 1
        if j < n and src[j] == "\\":
            j += 2
            while j < n and src[j] != "'":
                j += 1
        else:
            j += 1
        return min(j + 1, n)

    def read_string(i):
        # i at opening quote; returns (parts, index after the closing quote)
        i += 1
        parts, cur = [], []
        while i < n:
            c = src[i]
            if c == '"':
                i += 1
                break
            if c == "\\":
                e = src[i + 1] if i + 1 < n else ""
                if e == "u":
                    try:
                        cur.append(chr(int(src[i + 2:i + 6], 16)))
                    except ValueError:
                        pass
                    i += 6
                else:
                    cur.append(ESC.get(e, e))
                    i += 2
                continue
            if c == "$":
                nx = src[i + 1] if i + 1 < n else ""
                if nx == "{":
                    parts.append("".join(cur)); cur = []
                    parts.append(None)
                    i = skip_template(i + 2)
                    continue
                if nx.isalpha() or nx == "_":
                    j = i + 1
                    while j < n and (src[j].isalnum() or src[j] == "_"):
                        j += 1
                    parts.append("".join(cur)); cur = []
                    parts.append(None)
                    i = j
                    continue
            if c == "\n":      # unterminated: not a real literal
                break
            cur.append(c)
            i += 1
        parts.append("".join(cur))
        return parts, i

    while i < n:
        c = src[i]
        if c == "/" and src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "'":
            i = skip_char(i)
            continue
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            parts, i = read_string(i)
            # "a" + "b"  ->  one literal (long texts are split over lines in the source)
            while True:
                m = re.compile(r'\s*\+\s*"(?!"")').match(src, i)
                if not m:
                    break
                more, i = read_string(m.end() - 1)
                parts = parts[:-1] + [parts[-1] + more[0]] + more[1:]
            out.append(parts)
            continue
        i += 1
    return out


def ui_like(parts):
    text = "".join(p if p is not None else PH for p in parts)
    plain = "".join(p for p in parts if p is not None)
    if not (2 <= len(text) <= 220):
        return False
    letters = sum(ch.isalpha() for ch in plain)
    if letters < 3:
        return False
    if re.search(r"[{};<>=`\\|@#]|://|\.\.\/|::|->|\(\)|\.(kt|png|jpg|js|css|json|txt|xml|html|mp4|apk)\b", text):
        return False
    first = plain.lstrip()[:1]
    words = re.findall(r"[A-Za-z\u00C0-\u024F]{2,}", plain)
    # identifiers / keys / constants
    if " " not in text.strip() and not text[:1].isupper():
        return False
    if re.fullmatch(r"[A-Z0-9_]+", text):
        return False
    if re.fullmatch(r"[MmLlHhVvCcSsQqTtAaZz0-9.,\- ]+", text):      # SVG path data
        return False
    if re.search(r"[a-z][A-Z]", text) and " " not in text:
        return False
    counted = bool(re.fullmatch(r"\+" + PH + r" [a-z]+", text))     # "+3 more"
    if not (first.isupper() or counted or (len(words) >= 2 and " " in plain.strip())):
        return False
    # CSS / JS fragments
    if re.search(r"\b(var|function|return|const|let|px|rgba?|important|document|window|querySelector)\b", text):
        return False
    # adjacent placeholders cannot be matched
    for a, b in zip(parts, parts[1:]):
        if a is None and b is None:
            return False
    return True


def main():
    seen, order = set(), []
    for fn in sorted(os.listdir(SRC)):
        if not fn.endswith(".kt") or fn == "Lang.kt":
            continue
        with open(os.path.join(SRC, fn), encoding="utf-8") as f:
            src = f.read()
        for parts in scan(src):
            if not ui_like(parts):
                continue
            line = "".join(p if p is not None else PH for p in parts)
            line = line.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
            if line not in seen:
                seen.add(line); order.append(line)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(order) + "\n")
    print(len(order), "literals,", sum(PH in l for l in order), "with values ->", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
