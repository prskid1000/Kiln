"""Builds the kit catalog the agent searches: every public declaration in the kit
sources (signature + KDoc, grouped by category) plus the bundled libraries and
toolchain versions. Generated from the code, so it can't drift from it.

Writes catalog.json (for kit_search) and INDEX.md (names by category, for the
system prompt).
"""
import json
import re
from pathlib import Path

# Files without "// ---- section" markers, and the category their declarations go in.
FILE_CATEGORY = {
    "Components.kt": "Basics", "Data.kt": "State", "System.kt": "System", "Scaffolds.kt": "Screens & navigation",
    "Theme.kt": "Theme", "Backend.kt": "Backend", "Billing.kt": "Billing", "KilnActivity.kt": "Entry point",
    "Samples.kt": "Samples",
}
SKIP_FILES = {"Inspector.kt", "KilnCrash.kt"}
SECTION = re.compile(r"^// -{4,}\s*(.+?)\s*$")
DECL = re.compile(r"^(?P<ind>\s*)(?:@[\w.]+(?:\([^)]*\))?\s+)*(?P<mods>(?:(?:inline|suspend|data|enum|sealed|abstract|open|operator|"
                  r"private|internal|protected|override|const|lateinit|companion|fun interface)\s+)*)"
                  r"(?P<kw>fun|class|object|interface|val|var|typealias)\b")
NAME_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?((?:\w+(?:<[^>]*>)?\.)?\w+)\s*\(")
NAME_TYPE = re.compile(r"\b(?:class|object|interface|typealias)\s+(\w+)")
NAME_PROP = re.compile(r"\b(?:val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)")


def _clean_doc(lines):
    out = []
    for l in lines:
        l = l.strip()
        l = re.sub(r"^/\*\*\s?|\*/$|^\*\s?", "", l).rstrip()
        out.append(l)
    return "\n".join(out).strip()


def _signature(lines, i):
    """The declaration starting at lines[i], up to its body; returns (text, next index)."""
    depth, buf = 0, []
    while i < len(lines):
        line = lines[i]
        cut = None
        in_str = False
        for j, ch in enumerate(line):
            if ch == '"' and (j == 0 or line[j - 1] != "\\"):
                in_str = not in_str
            if in_str:
                continue
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
            elif depth == 0 and ch == "{":
                cut = j; break
            elif depth == 0 and ch == "=" and line[j - 1:j] not in ("!", "<", ">", "=") and line[j + 1:j + 2] not in ("=", ">"):
                # A typealias's "= …" is what it is (KDate = @Serializable(...) LocalDate): showing only "typealias
                # KDate" left the model unsure whether it could be saved.
                if re.search(r"\btypealias\b", " ".join(buf) + line[:j]):
                    continue
                cut = j; break
        buf.append((line[:cut] if cut is not None else line).rstrip())
        i += 1
        if cut is not None or depth <= 0:
            break
    text = re.sub(r"\s+", " ", " ".join(b.strip() for b in buf)).strip()
    text = re.sub(r"\(\s+", "(", text).replace(" )", ")").replace(", )", ")").rstrip(" {")
    return text, i


def parse_file(path: Path):
    lines = path.read_text(encoding="utf-8").splitlines()
    category = FILE_CATEGORY.get(path.name, "Other")
    entries, doc, composable, owner, i = [], None, False, None, 0
    while i < len(lines):
        line = lines[i]
        s = line.strip()
        m = SECTION.match(line)
        if m:
            category = m.group(1)[0].upper() + m.group(1)[1:]
            i += 1; continue
        if s.startswith("/**"):
            buf = [line]
            while "*/" not in lines[i] and i + 1 < len(lines):
                i += 1; buf.append(lines[i])
            doc = _clean_doc(buf); i += 1; continue
        if s.startswith("@") and not DECL.match(line):
            composable = composable or s.startswith("@Composable")
            i += 1; continue
        if line.startswith("}"):
            owner = None
        d = DECL.match(line)
        if d and len(d.group("ind")) in (0, 4) and (len(d.group("ind")) == 0 or owner):
            mods, kw, top = d.group("mods"), d.group("kw"), len(d.group("ind")) == 0
            composable = composable or "@Composable" in line
            sig, nxt = _signature(lines, i)
            sig = re.sub(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)+", "", sig)
            public = not re.search(r"\b(private|internal|protected|override)\b", mods)
            if kw == "fun":
                nm = NAME_FUN.search(sig)
            elif kw in ("val", "var"):
                nm = NAME_PROP.search(sig)
            else:
                nm = NAME_TYPE.search(sig)
            name = re.sub(r"<[^>]*>", "", nm.group(1)) if nm else None
            if name and re.match(r"\w*Scope\.", name):            # BoxScope.KToastHost is called as KToastHost
                name = name.split(".", 1)[1]
            if kw == "class" and "enum" in mods and "{" in line and "}" in line:  # one-line enums: show their values
                sig += " " + line[line.index("{"):].strip()
            if top and kw in ("class", "object", "interface", "fun") and name:
                body = lines[nxt - 1] if nxt > i else line
                opens = kw != "fun" and body.rstrip().endswith("{")
                owner = (name if public else None) if opens else None
            # A public function with an expression body and no declared type: its catalog signature shows no return
            # type, and the model can't see what it gets back (KSupabase.table returned a Table nobody could see).
            if kw == "fun" and name and public and not composable and re.search(r"\)\s*=", line) and not re.search(r"\)\s*:", sig):
                print(f"[catalog] warning: {category}: {name} has no declared return type (write `): Type =`)")
            # Same for a public property: `val bg get() = …` showed no type at all.
            if kw in ("val", "var") and name and public and not re.search(rf"\b{re.escape(name)}\s*:", sig):
                print(f"[catalog] warning: {category}: {name} has no declared type (write `val {name}: Type`)")
            if name and public and "companion" not in mods:
                if not top and name == "invoke":           # companion invoke = the constructor most code calls
                    entries.append(dict(name=owner, member=None, kind="constructor", category=category,
                                        signature=sig.replace("inline operator fun ", "").replace("invoke", owner or "", 1), doc=doc or ""))
                elif top or owner:
                    kind = ("enum" if "enum" in mods else "data class" if "data" in mods else kw) if kw != "fun" else \
                        ("composable" if composable else "function")
                    entries.append(dict(name=name if top else f"{owner}.{name}", member=None if top else name, owner=None if top else owner,
                                        kind=kind, category=category, signature=sig, doc=doc or ""))
            doc, composable = None, False
            i = max(nxt, i + 1); continue
        if s and not s.startswith("//"):
            doc, composable = (doc if s.startswith("@") else None), False
        i += 1
    for e in entries:
        e["file"] = path.name
    return entries


def libraries(index_txt: Path):
    seen = {}
    for line in index_txt.read_text().splitlines():
        parts = Path(line.strip()).stem.split("__")
        if len(parts) == 3:
            seen[(parts[0], parts[1])] = parts[2]
    return [dict(name=a, kind="library", category="Libraries", signature=f"{g}:{a}:{v}",
                 doc=f"Bundled library {g}:{a} version {v} — already on the classpath; don't add it to a build file.", file="")
            for (g, a), v in sorted(seen.items())]


def build(src_dir: Path, index_txt: Path, out_dir: Path, toolchain: dict):
    entries = []
    for f in sorted(src_dir.glob("*.kt")):
        if f.name not in SKIP_FILES:
            entries += parse_file(f)
    entries += libraries(index_txt)
    entries.append(dict(name="toolchain", kind="toolchain", category="Toolchain",
                        signature=", ".join(f"{k} {v}" for k, v in toolchain.items()),
                        doc="What the on-device build uses. " + "; ".join(f"{k}: {v}" for k, v in toolchain.items()), file=""))
    (out_dir / "catalog.json").write_text(json.dumps(entries, ensure_ascii=False, indent=0), encoding="utf-8")

    # Names by category; an object's or class's members go in brackets after it.
    members = {}
    for e in entries:
        if e.get("owner"):
            members.setdefault(e["owner"], []).append(e["member"])
    cats = {}
    for e in entries:
        if e.get("owner") or e["kind"] in ("library", "toolchain", "constructor"):
            continue
        n = e["name"]
        if n in members:
            n += "(" + ", ".join(dict.fromkeys(members[n])) + ")"
        cats.setdefault(e["category"], []).append(n)
    out = [f"- **{c}**: " + ", ".join(dict.fromkeys(ns)) for c, ns in cats.items()]
    libs = [e["signature"].split(":")[1] for e in entries if e["kind"] == "library"]
    out.append(f"- **Libraries** (on the classpath, {len(libs)}): " + ", ".join(sorted(set(
        l for l in libs if not l.endswith(("-android", "-jvm", "-desktop"))))))
    out.append("- **Toolchain**: " + entries[-1]["signature"])
    # The components apps use most, with their parameters: runs 11–13 guessed names (onValueChange vs onChange,
    # body vs message) or spent a dozen kit_search calls looking them up.
    out.append("\n## Common components — parameters (required: type; optional: name only)")
    by_name = {e["name"]: e for e in entries if e["kind"] in ("function", "composable", "class") and not e.get("owner")}
    for n in COMMON:
        e = by_name.get(n)
        if e:
            out.append("- " + compact_signature(n, e["signature"]))
    (out_dir / "INDEX.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    return entries


COMMON = ["KilnScreen", "KilnTabs", "KCrudList", "KListRow", "KSection", "KCardBox", "KButton", "KFab", "KTextField",
          "KTextArea", "KSearchBar", "KSelect", "KSegmented", "KChipGroup", "KSwitch", "KSwitchRow", "KCheckbox", "KDateField",
          "KTimeField", "KAlert", "KConfirm", "KDialog", "KBottomSheet", "KProgressBar", "KDonutChart", "KBarChart",
          "KEmptyState", "KStat", "KTag", "rememberKToast"]


def compact_signature(name: str, sig: str) -> str:
    """`KTextField(value: String, onValueChange: (String) -> Unit; label, placeholder, error, …)` from a full signature."""
    # Doc comments inside the parameter list, and the ">" of "->" (not a closing bracket).
    sig = re.sub(r"/\*.*?\*/", "", sig, flags=re.S).replace("->", "→")
    start = sig.find("(")
    if start < 0:
        return name
    depth, end = 0, len(sig)
    for i in range(start, len(sig)):
        if sig[i] in "([{<":
            depth += 1
        elif sig[i] in ")]}>":
            depth -= 1
            if depth == 0:
                end = i
                break
    params, cur, d = [], "", 0
    for ch in sig[start + 1:end]:
        if ch in "([{<":
            d += 1
        elif ch in ")]}>":
            d -= 1
        if ch == "," and d == 0:
            params.append(cur.strip()); cur = ""
        else:
            cur += ch
    if cur.strip():
        params.append(cur.strip())
    required, optional = [], []
    for p in params:
        p = re.sub(r"^(?:@\w+\s+)*(?:vararg\s+|noinline\s+|crossinline\s+)*", "", p).strip()
        pname = p.split(":")[0].strip()
        if not pname or pname == "modifier":
            continue
        if "=" in p.split(":", 1)[-1]:
            optional.append(pname)
        else:
            required.append(re.sub(r"\s+", " ", p.split("=")[0]).replace("@Composable ", ""))
    text = (f"{name}(" + ", ".join(required) + ("; " + ", ".join(optional) if optional else "") + ")").replace("→", "->")
    return text if len(text) <= 260 else text[:257] + "…)"


if __name__ == "__main__":
    import sys
    repo = Path(__file__).resolve().parent.parent
    es = build(repo / "kit/src/main/java/app/kiln/kit", repo / "kit/build/kit-export/index.txt", Path(sys.argv[1] if len(sys.argv) > 1 else "."),
               {"Kotlin": "2.4.20", "compileSdk": 36, "minSdk": 30})
    print(len(es), "entries")
