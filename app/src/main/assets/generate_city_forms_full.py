"""
Generuje pelna odmiane DWUCZLONOWYCH nazw miejscowosci dla LynxMask.

Kontekst: cities_forms.json (plaska lista, ~179k form) mial PELNA odmiane tylko dla
nazw JEDNOWYRAZOWYCH ("Warszawa" -> "Warszawie" itd.). Nazwy DWUWYRAZOWE ("Jelenia
Gora", "Zielona Gora") mialy w pliku WYLACZNIE mianownik — "w Jeleniej Gorze" nie
bylo maskowane (bug zdiagnozowany 04.07.2026, patrz project_city_declension_task.md
w pamieci Claude). Ten skrypt DOKLADA nowe, odmienione formy dla ~7300 nazw
dwuwyrazowych z cities.json, nie ruszajac tego co juz dziala (nazwy jednowyrazowe,
mianownik dwuwyrazowych).

Wzorce dwuczlonowych nazw geograficznych po polsku (rozne szyki):
  A. przymiotnik + rzeczownik  ("Zielona Gora", "Nowy Targ")
  B. rzeczownik + przymiotnik  ("Dabrowa Gornicza" — rzadszy szyk, historyczny)
  C. przymiotnik + przymiotnik ("Biala Podlaska" — oba czlony to przymiotniki,
     brak jawnego rzeczownika-glowy)
Skrypt probuje kolejno A, B, C — pierwsza strategia ktora znajdzie pasujaca rodzajem
pare (przymiotnik + rzeczownik/przymiotnik) wygrywa. Jesli zadna nie pasuje, ale
jeden z czlonow ma jakakolwiek odmiane rzeczownikowa — odmienia TYLKO ten czlon,
drugi zostaje bez zmian (bezpieczny fallback, analogiczny do generate_street_forms.py).
Jesli NIC sie nie da ustalic — nazwa zostaje bez zmian (bez regresji vs stan obecny).

Nazwy 3+ wyrazowe (np. "Grabow nad Pilica", "Brzezie k. Sulechowa" — ~194 w bazie,
zazwyczaj czlon geograficzny + przyimek + rzeka) sa POMIJANE w tej wersji — osobny,
rzadszy wzorzec, nizszy priorytet (patrz TODO w pamieci project_city_declension_task.md).

Wymaga: morfeusz2 (pip install morfeusz2)
Wejscie:  cities.json        — plaska lista nazw bazowych (mianownik)
          cities_forms.json  — istniejacy plik do ROZSZERZENIA (nie nadpisuje calosci)
Wyjscie:  cities_forms.json  — istniejace formy + nowe formy dwuwyrazowe

Uzycie:
  python generate_city_forms_full.py
  python generate_city_forms_full.py --sample   # tylko 5 znanych przypadkow testowych, bez zapisu
"""

import json
import argparse
import sys

try:
    import morfeusz2
except ImportError:
    print("BLAD: morfeusz2 nie jest zainstalowany (pip install morfeusz2).")
    sys.exit(1)


CASES = {"nom", "gen", "dat", "acc", "inst", "loc", "voc"}
GEO_NOUN_QUALIFIERS = {"nazwa_geograficzna", "nazwa_pospolita", "człon_nazwy_geograficznej"}


def strip_diacritics(text: str) -> str:
    table = str.maketrans("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", "acelnoszzACELNOSZZ")
    return text.translate(table)


def analyze_word(morf, word):
    """Zwraca (subst_interpretacje, adj_interpretacje) dla slowa — kazda z
    lemma/rodzaj/kwalifikatorami z Morfeusza (tylko liczba pojedyncza, tylko
    stopien rownowazny 'pos' dla przymiotnikow — nie chcemy "zieleńsza Gora")."""
    subst, adj = [], []
    for _, _, (orth, lemma, tag, quals, _) in morf.analyse(word):
        parts = tag.split(":")
        pos = parts[0]
        if pos == "subst":
            if "sg" not in parts[1].split("."):
                continue
            gender = parts[3] if len(parts) > 3 else None
            subst.append({"lemma": lemma.split(":")[0], "gender": gender, "quals": set(quals)})
        elif pos == "adj":
            if len(parts) < 5 or parts[4] != "pos":
                continue
            adj.append({"lemma": lemma.split(":")[0], "gender": parts[3], "quals": set(quals)})
    return subst, adj


def best_noun(subst_list, require_geo=False):
    """Preferuj interpretacje z tagiem geograficznym/pospolitym nad 'nazwisko'."""
    geo = [s for s in subst_list if s["quals"] & GEO_NOUN_QUALIFIERS]
    if geo:
        return geo[0]
    if require_geo:
        return None
    non_surname = [s for s in subst_list if "nazwisko" not in s["quals"]]
    if non_surname:
        return non_surname[0]
    return subst_list[0] if subst_list else None


def matching_adj(adj_list, gender):
    for a in adj_list:
        if not gender or (set(a["gender"].split(".")) & set(gender.split("."))):
            return a
    return None


def case_forms(morf, lemma, pos, gender):
    """Formy danego lemma/pos (liczba pojedyncza, stopien 'pos' dla przymiotnikow,
    opcjonalnie ograniczone do zadanego rodzaju), pogrupowane wg przypadka."""
    result = {}
    for orth, lem, tag, quals, _ in morf.generate(lemma):
        parts = tag.split(":")
        if parts[0] != pos:
            continue
        if "sg" not in parts[1].split("."):
            continue
        if pos == "adj":
            if len(parts) < 5 or parts[4] != "pos":
                continue
            g = parts[3]
        else:
            g = parts[3] if len(parts) > 3 else None
        if gender and g and not (set(g.split(".")) & set(gender.split("."))):
            continue
        for c in parts[2].split("."):
            if c in CASES:
                result.setdefault(c, set()).add(orth)
    return result


def pair_forms(forms_a, forms_b):
    """Iloczyn form wspolnych przypadkow dwoch zbiorow (case -> set)."""
    common = set(forms_a) & set(forms_b)
    out = set()
    for c in common:
        for fa in forms_a[c]:
            for fb in forms_b[c]:
                out.add((fa, fb))
    return out


def decline_two_word(morf, w1, w2):
    """Zwraca set nowych stringow 'Word1 Word2' (odmienionych), bez formy
    mianownikowej (ta juz jest w bazie). Pusty set = nie udalo sie nic ustalic."""
    subst1, adj1 = analyze_word(morf, w1)
    subst2, adj2 = analyze_word(morf, w2)

    # A: word1 = przymiotnik, word2 = rzeczownik ("Zielona Gora", "Nowy Targ")
    noun2 = best_noun(subst2, require_geo=True) or best_noun(subst2)
    if noun2 and adj1:
        a = matching_adj(adj1, noun2["gender"])
        if a:
            fn = case_forms(morf, noun2["lemma"], "subst", None)
            fa = case_forms(morf, a["lemma"], "adj", noun2["gender"])
            pairs = pair_forms(fa, fn)
            if pairs:
                return {f"{x} {y}" for x, y in pairs}

    # B: word1 = rzeczownik, word2 = przymiotnik ("Dabrowa Gornicza")
    noun1 = best_noun(subst1, require_geo=True) or best_noun(subst1)
    if noun1 and adj2:
        a = matching_adj(adj2, noun1["gender"])
        if a:
            fn = case_forms(morf, noun1["lemma"], "subst", None)
            fa = case_forms(morf, a["lemma"], "adj", noun1["gender"])
            pairs = pair_forms(fn, fa)
            if pairs:
                return {f"{x} {y}" for x, y in pairs}

    # C: oba czlony to przymiotniki, zgodne rodzajem ("Biala Podlaska")
    if adj1 and adj2:
        for a1 in adj1:
            a2 = matching_adj(adj2, a1["gender"])
            if a2:
                f1 = case_forms(morf, a1["lemma"], "adj", a1["gender"])
                f2 = case_forms(morf, a2["lemma"], "adj", a1["gender"])
                pairs = pair_forms(f1, f2)
                if pairs:
                    return {f"{x} {y}" for x, y in pairs}

    # Fallback: odmien TYLKO ten czlon ktory ma jakakolwiek odmiane rzeczownikowa,
    # drugi zostaw bez zmian — bezpieczniej niz nic (ale mniej precyzyjnie).
    if noun2:
        fn = case_forms(morf, noun2["lemma"], "subst", None)
        return {f"{w1} {f}" for fs in fn.values() for f in fs}
    if noun1:
        fn = case_forms(morf, noun1["lemma"], "subst", None)
        return {f"{f} {w2}" for fs in fn.values() for f in fs}

    return set()


def to_title_case(phrase: str) -> str:
    return " ".join(w[:1].upper() + w[1:] if w else w for w in phrase.split(" "))


SAMPLE_NAMES = ["Jelenia Góra", "Zielona Góra", "Nowy Targ", "Biała Podlaska", "Dąbrowa Górnicza"]


def run_sample(morf):
    for name in SAMPLE_NAMES:
        w1, w2 = name.split(" ", 1)
        forms = decline_two_word(morf, w1, w2)
        print(f"=== {name} ({len(forms)} nowych form) ===")
        for f in sorted(forms):
            print(" ", to_title_case(f))


def process(cities_file: str, forms_file: str, output_file: str):
    print(f"Wczytuję bazę nazw: {cities_file}")
    with open(cities_file, encoding="utf-8") as f:
        base_names = json.load(f)

    print(f"Wczytuję istniejące formy: {forms_file}")
    with open(forms_file, encoding="utf-8") as f:
        existing = set(json.load(f))

    two_word = [n for n in base_names if len(n.split()) == 2]
    print(f"Nazw dwuwyrazowych do przetworzenia: {len(two_word)}")

    print("Inicjalizuję Morfeusz2...")
    morf = morfeusz2.Morfeusz()

    new_forms = set()
    unresolved = []
    for i, name in enumerate(two_word):
        if i % 500 == 0:
            print(f"  {i}/{len(two_word)}...")
        w1, w2 = name.split(" ", 1)
        forms = decline_two_word(morf, w1, w2)
        if not forms:
            unresolved.append(name)
            continue
        for form in forms:
            titled = to_title_case(form)
            if titled.lower() != name.lower():
                new_forms.add(titled)

    print(f"Nowych form (dwuwyrazowe, bez duplikatów z istniejącymi): "
          f"{len(new_forms - existing)}")
    print(f"Nazw bez rozstrzygnięcia (zostają tylko w mianowniku, jak dotąd): {len(unresolved)}")

    merged = sorted(existing | new_forms)
    print(f"Zapisuję {len(merged)} kluczy do: {output_file}")
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=2)

    print("Gotowe.")
    return unresolved


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cities", default="cities.json")
    parser.add_argument("--forms", default="cities_forms.json")
    parser.add_argument("--output", default="cities_forms.json")
    parser.add_argument("--sample", action="store_true",
                         help="Tylko test na 5 znanych przypadkach, bez zapisu do pliku")
    args = parser.parse_args()

    if args.sample:
        run_sample(morfeusz2.Morfeusz())
    else:
        process(args.cities, args.forms, args.output)
