"""
Generuje city_surname_overlap.json dla LynxMask.

Słowa będące JEDNOCZEŚNIE (a) drugim członem dwuczłonowej nazwy miejscowości
(cities_forms.json) i (b) nazwiskiem z top-1000 (surnames_top1000.json).

Przykład problemu który to rozwiązuje: "Zielona Góra", "Jelenia Góra" — "Góra" samo,
poza kontekstem adresowym (np. w zdaniu bez "ul."/numeru/przyimka), było maskowane
jako OSOBA przez regułę "samo nazwisko" w NameEngine.kt, bo "góra" jest realnym
nazwiskiem w top-1000. Lista z tego skryptu jest dodatkową białą listą w
NameEngine.isOnWhiteList() — chroni CAŁĄ klasę takich słów (nie tylko "górę"),
bez poszerzania sprawdzania na cały (bardzo duży) słownik miejscowości, co obniżyłoby
recall OSOBA dla zwykłych nazwisk gdzie indziej.

Wejście:  cities_forms.json      — płaska lista wszystkich form nazw miejscowości
          surnames_top1000.json  — {nazwisko: [formy odmiany...]}
Wyjście:  city_surname_overlap.json — lista słów (posortowana), format jak WHITE_LIST_CITIES

Uruchom ponownie jeśli zmienią się cities_forms.json lub surnames_top1000.json —
przecięcie nie jest ręcznie utrzymywane, tylko liczone na nowo z aktualnych słowników.
"""

import json


def process(
    cities_file: str = "cities_forms.json",
    surnames_file: str = "surnames_top1000.json",
    output_file: str = "city_surname_overlap.json",
):
    with open(cities_file, encoding="utf-8") as f:
        cities = json.load(f)
    with open(surnames_file, encoding="utf-8") as f:
        surnames = json.load(f)

    surname_keys = set(surnames.keys())

    second_components = set()
    for form in cities:
        words = form.lower().split()
        if len(words) == 2:
            second_components.add(words[1])

    overlap = sorted(second_components & surname_keys)

    print(f"Miejscowości (form, płasko): {len(cities)}")
    print(f"Nazwisk (top-1000, kluczy): {len(surnames)}")
    print(f"Drugich członów dwuwyrazowych nazw miejscowości: {len(second_components)}")
    print(f"Przecięcie (do ochrony w isOnWhiteList): {len(overlap)}")
    print(overlap)

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(overlap, f, ensure_ascii=False, indent=2)
    print(f"Zapisano: {output_file}")


if __name__ == "__main__":
    process()
