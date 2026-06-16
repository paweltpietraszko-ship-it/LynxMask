"""
Generuje street_names.json z odmianami dla LynxMask.
Wymaga: morfeusz2 (pip install morfeusz2)
Wejście:  street_names_base.txt  — jedna nazwa ulicy na linię (mianownik)
Wyjście:  street_names.json      — {"lipowa": ["lipowej","lipową",...], ...}
"""

import json
import argparse
import sys

try:
    import morfeusz2
except ImportError:
    print("BŁĄD: morfeusz2 nie jest zainstalowany.")
    sys.exit(1)


def strip_diacritics(text: str) -> str:
    table = str.maketrans("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", "acelnoszzACELNOSZZ")
    return text.translate(table)


def get_forms(morf, word: str) -> set:
    """Zwraca wszystkie formy fleksyjne słowa przez Morfeusz2.
    Morfeusz2 zwraca krotki 5-elementowe: (orth, lemma, tag, qub1, qub2)
    """
    forms = set()
    word_lower = word.lower()
    forms.add(word_lower)

    lemas = set()
    for interp in morf.analyse(word):
        # interp = (node_from, node_to, (orth, lemma, tag, qub1, qub2))
        triple = interp[2]
        lemma = triple[1].split(":")[0].lower()
        lemas.add(lemma)

    for lema in lemas:
        for item in morf.generate(lema):
            # item = (orth, lemma, tag, qub1, qub2)
            orth = item[0].lower()
            if len(orth) >= 3:
                forms.add(orth)

    return forms


def process(input_file: str, output_file: str):
    print(f"Wczytuję ulice z: {input_file}")
    with open(input_file, encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip()]

    print(f"Inicjalizuję Morfeusz2...")
    morf = morfeusz2.Morfeusz()

    result = {}

    for i, street in enumerate(lines):
        if i % 500 == 0:
            print(f"  {i}/{len(lines)}...")

        words = street.lower().split()
        key = street.lower()

        if len(words) == 1:
            forms = get_forms(morf, words[0])
            forms.discard(key)
            result[key] = sorted(forms)
        else:
            # Wieloczłonowa: odmieniaj pierwszy człon, reszta bez zmian
            first_forms = get_forms(morf, words[0])
            rest = " ".join(words[1:])
            multi_forms = set()
            for ff in first_forms:
                multi_forms.add(f"{ff} {rest}")
            multi_forms.discard(key)
            result[key] = sorted(multi_forms)

        # Wariant ASCII
        ascii_key = strip_diacritics(key)
        if ascii_key != key:
            ascii_forms = {strip_diacritics(f) for f in result[key]}
            ascii_forms.discard(ascii_key)
            result[ascii_key] = sorted(ascii_forms)

    print(f"Zapisuję {len(result)} kluczy do: {output_file}")
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"Gotowe.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input",  default="street_names_base.txt")
    parser.add_argument("--output", default="street_names.json")
    args = parser.parse_args()
    process(args.input, args.output)
