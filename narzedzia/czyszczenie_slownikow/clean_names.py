"""clean_names.py <in.json> <out.json> [report.json] [--closed] [--no-name-rule]
Czyści słownik baza -> [formy] z form obcych słów (Morfeusz2). Format pliku 1:1. Źródło błędu: generator brał formy
każdej interpretacji homografu (Tom -> „to+m” -> być/jest; Ala -> ale; Kim -> kto). Użycie 21.10: imiona z --closed (L2),
nazwiska bez (L1). --closed (f): forma inna niż klucz z interpretacją klasy zamkniętej (spójnik, partykuła, czasownik,
przysłówek, zaimek) jest usuwana, chyba że to forma l.poj. tego samego imienia.
Zachowaj formę gdy: (a) == baza; (b) którakolwiek interpretacja Morfeusza ma lemat == baza
(lemat przed ':', bez wielkości liter i bez ogonków); (c) jest wariantem ASCII formy zachowanej
wg (a)/(b); (e) [domyślnie, wyłącz --no-name-rule] Morfeusz ma dla formy interpretację z kwalifikatorem imię/nazwisko (inny lemat-imię, np. męski odpowiednik); (d) Morfeusz nie zna formy (ign) i zaczyna się od pierwszych 3 liter bazy.
Python: .venv ma morfeusz2."""
import json, sys, re, unicodedata, io
sys.stdout.reconfigure(encoding="utf-8")
import morfeusz2
M = morfeusz2.Morfeusz()
_PL = str.maketrans("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", "acelnoszzACELNOSZZ")
def fold(s): return s.translate(_PL).lower()
_cache = {}
# (f) słowa funkcyjne/czasowniki: forma (inna niż klucz) z interpretacją z zamkniętej klasy -> usuń, nawet gdy ma też interpretację-imię
CLOSED = {"conj", "comp", "qub", "prep", "interj", "fin", "praet", "inf", "impt", "bedzie", "pcon", "pant", "imps", "aglt", "winien", "pred", "adv"}
PRON = set("ten to ty ja on ona ono my wy oni one kto co który jaki taki mój twój swój nasz wasz jego jej ich ów wszystek każdy żaden inny sam jeden być mieć nic nikt".split())
CLOSEDRULE = "--closed" in sys.argv
def analyse(w):
    """-> (set lematów (fold), czy_znane)"""
    if w in _cache: return _cache[w]
    lem, known, nm, cl, sgn = set(), False, False, False, set()
    for c in {w, w.capitalize()}:
        try: it = M.analyse(c)
        except Exception: continue
        for i in it:
            tag = i[2][2]
            if tag == "ign": continue
            known = True
            lem.add(fold(i[2][1].split(":")[0]))
            if {q.split("_(")[0] for q in i[2][3]} & {"imię", "nazwisko"}:
                nm = True
                if ":sg:" in tag and not tag.startswith("depr"): sgn.add(fold(i[2][1].split(":")[0]))
            if tag.split(":")[0] in CLOSED or i[2][1].split(":")[0] in PRON: cl = True
    _cache[w] = (lem, known, nm, cl, sgn)
    return _cache[w]

NAMERULE = "--no-name-rule" not in sys.argv
KEYS = set()
def classify(base, forms):
    """-> dict forma -> powód zachowania (a/b/c/d) lub None"""
    fb = fold(base)
    toks = {t for t in re.split(r"[ \-]", fb) if len(t) >= 3} if re.search(r"[ \-]", fb) else set()
    why = {}
    for f in forms:
        if f == base: why[f] = "a"; continue
        lem, known, nm, cl, sgn = analyse(f)
        if CLOSEDRULE and cl and f not in KEYS and fb not in sgn: why[f] = None
        elif fb in lem or (lem & toks): why[f] = "b"
        elif NAMERULE and nm: why[f] = "e"
        elif not known and fold(f).startswith(fb[:3]): why[f] = "d"
        else: why[f] = None
    kept_fold = {fold(f) for f, r in why.items() if r in ("a", "b", "e")}
    for f in forms:
        if why[f] is None and fold(f) in kept_fold and not (CLOSEDRULE and analyse(f)[3] and f not in KEYS and fb not in analyse(f)[4]): why[f] = "c"
    return why

def detect_style(raw):
    # names: jedna linia na klucz, lista inline; surnames: indent=2
    return "inline" if re.match(r'\{\n  "[^"\n]+": \[.*\],?\n', raw) else "indent"

def dump(d, style):
    if style == "indent":
        return json.dumps(d, ensure_ascii=False, indent=2)
    keys = list(d)
    lines = ['  %s: %s%s' % (json.dumps(k, ensure_ascii=False), json.dumps(d[k], ensure_ascii=False), "," if i < len(keys) - 1 else "")
             for i, k in enumerate(keys)]
    return "{\n" + "\n".join(lines) + "\n}"

def main():
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    inp, outp = a[0], a[1]
    rep = a[2] if len(a) > 2 else outp + ".report.json"
    raw = open(inp, encoding="utf-8", newline="").read()
    d = json.loads(raw)
    nl = "\r\n" if "\r\n" in raw else "\n"
    trail = raw.endswith("\n")
    style = next((st for st in ("indent", "inline") if dump(d, st).replace("\n", nl) + (nl if trail else "") == raw), "indent")
    roundtrip = dump(d, style).replace("\n", nl) + (nl if trail else "")
    print("format:", style, "roundtrip identyczny:", roundtrip == raw)
    KEYS.update(d)
    out, removed, reasons = {}, {}, {}
    for base, forms in d.items():
        why = classify(base, forms)
        out[base] = [f for f in forms if why[f]]
        rm = [f for f in forms if not why[f]]
        if rm: removed[base] = rm
        for f in forms:
            if why[f]: reasons[why[f]] = reasons.get(why[f], 0) + 1
    txt = dump(out, style).replace("\n", nl) + (nl if trail else "")
    with open(outp, "w", encoding="utf-8", newline="") as fh: fh.write(txt)
    tot_before = sum(len(v) for v in d.values()); tot_rm = sum(len(v) for v in removed.values())
    json.dump({"before": tot_before, "removed": tot_rm, "keys_affected": len(removed), "kept_by": reasons,
               "empty_after": [k for k, v in out.items() if not v and d[k]], "removed_by_base": removed},
              open(rep, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("przed", tot_before, "usunięto", tot_rm, "kluczy z usunięciami", len(removed), "kept_by", reasons)
main()
