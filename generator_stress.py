#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generator_stress.py — Benchmark „trudny” dla OSOBA (duże pule słownikowe)
==========================================================================
Cel: wymusić naprawy OGÓLNE, nie łatki pod ~25 nazwisk z generator.py.

Różnice względem generator_clean.py / generator.py:
  - Imiona losowane z names_inflected.json (~3609 kluczy), nie z puli 24+24.
  - Nazwiska losowane ze surnames_extended.json (~39k), ze STRATYFIKACJĄ:
      * extended_only — tylko w 39k, poza top-1000 (główna korzyść dużego słownika)
      * top1000       — potwierdzone popularne nazwiska
      * collision     — słowa w 39k identyczne z pospolitym / zwierzę / zawód
      * full          — losowe z całej puli 39k
  - ground_truth.json zawiera sekcję "stress" z tierem każdej osoby (osobna metryka recall).
  - Szablony dokumentów: te same co generator_clean (scope business).

Użycie:
  python generator_stress.py --count 300 --output dataset_stress --seed 42
  python generator_stress.py --count 50  --output dataset_stress --seed 1 --quick

Potem (Paweł):
  gradlew :app:testDebugUnitTest --tests "*.StressBenchmarkTest"

Wymaga plików (repo):
  app/src/main/assets/names_inflected.json
  app/src/main/assets/surnames_top1000.json
  app/src/main/assets/surnames_extended.json
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
from typing import Any

import generator as gen
import generator_clean as gclean

# ── Ścieżki do słowników aplikacji ───────────────────────────────────────────

def _repo_root() -> Path:
    return Path(__file__).resolve().parent


def _load_json_keys(path: Path) -> list[str]:
    if not path.exists():
        raise FileNotFoundError(f"Brak pliku słownika: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    return sorted(data.keys())


# Imiona męskie kończące się na -a (wyjątki od heurystyki żeńskiej)
_MALE_A_NAMES = frozenset({
    "barnaba", "kuba", "bonawentura", "kosma", "mustafa", "jarema", "sasza",
    "boryna", "jona", "micha", "seba",
})


def _capitalize_pl(word: str) -> str:
    if not word:
        return word
    return word[0].upper() + word[1:]


def _display_surname(key: str, male: bool) -> str:
    """Klucz słownika (lowercase) → forma w mianowniku w tekście dokumentu."""
    s = key
    if not male:
        if s.endswith("dzki"):
            s = s[:-4] + "dzka"
        elif s.endswith("cki"):
            s = s[:-3] + "cka"
        elif s.endswith("ski"):
            s = s[:-3] + "ska"
    return _capitalize_pl(s)


def _classify_surname_tier(key: str, top1000: set[str], collision: set[str]) -> str:
    k = key.lower()
    if k in collision:
        return "collision"
    if k in top1000:
        return "top1000"
    return "extended_only"


# Kandydaci na kolizje — muszą być w surnames_extended, inaczej pomijane
_COLLISION_CANDIDATES = [
    "osoba", "zapłaty", "zaplaty", "łączna", "laczna", "działający", "dzialajacy",
    "data", "zając", "zajac", "wróbel", "wrobel", "sikora", "kołodziej", "kolodziej",
    "dudek", "biała", "biala", "zapłaty", "mazur", "góra", "gora",
]


def _build_pools(assets: Path) -> dict[str, Any]:
    names_keys = _load_json_keys(assets / "names_inflected.json")
    top1000_keys = set(_load_json_keys(assets / "surnames_top1000.json"))
    extended_keys = _load_json_keys(assets / "surnames_extended.json")
    extended_set = set(extended_keys)

    street_keys = _load_json_keys(assets / "street_names.json")
    cities_raw = json.loads((assets / "cities.json").read_text(encoding="utf-8"))
    cities = sorted(cities_raw) if isinstance(cities_raw, list) else sorted(cities_raw.keys())

    female_names = [k for k in names_keys if len(k) >= 3 and k.endswith("a") and k not in _MALE_A_NAMES]
    male_names = [k for k in names_keys if len(k) >= 3 and k not in female_names]

    if not male_names:
        male_names = names_keys[:]
    if not female_names:
        female_names = names_keys[:]

    top1000_list = sorted(top1000_keys)
    extended_only = [k for k in extended_keys if k not in top1000_keys]
    collision = [k for k in _COLLISION_CANDIDATES if k in extended_set]
    if not collision:
        # fallback: kilka znanych z TODO
        collision = [k for k in ("zając", "wrobel", "sikora", "kołodziej", "osoba") if k in extended_set]

    return {
        "male_names": male_names,
        "female_names": female_names,
        "top1000": top1000_list,
        "top1000_set": top1000_keys,
        "extended_all": extended_keys,
        "extended_only": extended_only,
        "collision": collision,
        "collision_set": set(collision),
        "street_keys": street_keys,
        "cities": cities,
    }


def _pick_surname_key(rng: random.Random, pools: dict[str, Any]) -> tuple[str, str]:
    """Zwraca (klucz_słownika, tier). Wagi domyślne: trudne przypadki częściej."""
    tier = rng.choices(
        population=["extended_only", "top1000", "collision", "full"],
        weights=[35, 20, 20, 25],
        k=1,
    )[0]
    if tier == "extended_only" and pools["extended_only"]:
        key = rng.choice(pools["extended_only"])
    elif tier == "collision" and pools["collision"]:
        key = rng.choice(pools["collision"])
    elif tier == "top1000":
        key = rng.choice(pools["top1000"])
    else:
        key = rng.choice(pools["extended_all"])
    actual_tier = _classify_surname_tier(key, pools["top1000_set"], pools["collision_set"])
    return key, actual_tier


def _install_stress_gen_person(pools: dict[str, Any]) -> None:
    """Podmienia gen.gen_person i pule w generator.py / Person w generator_clean."""

    def stress_gen_person(rng: random.Random) -> dict[str, Any]:
        male = rng.random() < 0.5
        first_key = rng.choice(pools["male_names"] if male else pools["female_names"])
        last_key, tier = _pick_surname_key(rng, pools)
        first = _capitalize_pl(first_key)
        last = _display_surname(last_key, male)
        return {
            "first": first,
            "last": last,
            "full": f"{first} {last}",
            "_stress_first_key": first_key,
            "_stress_surname_key": last_key,
            "_stress_surname_tier": tier,
        }

    gen.gen_person = stress_gen_person  # type: ignore[method-assign]

    # Person w prozie (generator_clean) — te same pule imion/nazwisk
    _orig_person_init = gclean.Person.__init__

    def _stress_person_init(self, rng: random.Random) -> None:
        p = stress_gen_person(rng)
        self.male = p["first"] in {_capitalize_pl(k) for k in pools["male_names"]}
        self.first = p["first"]
        self.last = p["last"]
        self._stress = {
            "first_key": p["_stress_first_key"],
            "surname_key": p["_stress_surname_key"],
            "surname_tier": p["_stress_surname_tier"],
        }

    gclean.Person.__init__ = _stress_person_init  # type: ignore[method-assign]

    # Szerokie pule dla ewentualnych bezpośrednich odwołań (nie używane po patchu gen_person)
    gen._M_FIRST = [_capitalize_pl(k) for k in pools["male_names"]]
    gen._F_FIRST = [_capitalize_pl(k) for k in pools["female_names"]]
    gen._M_LAST = [_display_surname(k, True) for k in pools["extended_all"]]
    gen._F_LAST = [_display_surname(k, False) for k in pools["extended_all"]]


def _format_street(key: str) -> str:
    """Klucz street_names → 'ul. Leśna' (bez zgadywania al./pl.)."""
    return f"ul. {_capitalize_pl(key)}"


def _install_stress_gen_address(pools: dict[str, Any]) -> None:
    """Adresy z street_names.json (~11k) i cities.json (~31k), nie z ~28 ulic."""

    def stress_gen_address(rng: random.Random) -> dict[str, str]:
        street = _format_street(rng.choice(pools["street_keys"]))
        house = str(rng.randint(1, 200))
        if rng.random() < 0.4:
            house += f"/{rng.randint(1, 50)}"
        city = rng.choice(pools["cities"])
        postcode = rng.choice(gen._POSTCODES)
        return {
            "street": street, "house": house,
            "city": city, "postcode": postcode,
            "full": f"{street} {house}, {postcode} {city}",
        }

    gen.gen_address = stress_gen_address  # type: ignore[method-assign]
    gen._CITIES = pools["cities"]
    gen._STREETS = [_format_street(k) for k in pools["street_keys"][:500]]  # fallback list


# Klucze encji GT, które mogą zawierać osobę
_PERSON_ENTITY_KEYS = frozenset({
    "imie_nazwisko", "imie_nazwisko_nabywcy", "imie_nazwisko_zleceniodawca",
    "imie_nazwisko_zleceniobiorca", "autor", "osoba", "skarzacy", "oskarzony",
    "mentor", "nadawca", "odbiorca", "urzednik", "narrator", "przyjaciel",
})


def _extract_stress_from_entities(entities: dict[str, Any], pools: dict[str, Any]) -> list[dict[str, Any]]:
    """Taguje osoby w GT po wartości tekstowej (imię + nazwisko w dokumencie)."""
    persons: list[dict[str, Any]] = []
    for key, value in entities.items():
        if key not in _PERSON_ENTITY_KEYS or not isinstance(value, str):
            continue
        parts = value.strip().split()
        if len(parts) < 2:
            continue
        surname_display = parts[-1]
        # Odtwórz klucz słownika z formy wyświetlanej (przybliżenie)
        surname_key = surname_display.lower()
        # żeńskie -ska → -ski dla lookupu
        if surname_key.endswith("ska"):
            surname_key = surname_key[:-3] + "ski"
        elif surname_key.endswith("cka"):
            surname_key = surname_key[:-3] + "cki"
        elif surname_key.endswith("dzka"):
            surname_key = surname_key[:-4] + "dzki"
        tier = _classify_surname_tier(
            surname_key,
            pools["top1000_set"],
            pools["collision_set"],
        )
        if surname_key not in pools["extended_all"] and surname_key not in pools["top1000_set"]:
            # forma nieodmienna / inna — szukaj po końcówce display
            for k in pools["extended_all"]:
                if _display_surname(k, True).lower() == surname_display.lower() or \
                   _display_surname(k, False).lower() == surname_display.lower():
                    surname_key = k
                    tier = _classify_surname_tier(k, pools["top1000_set"], pools["collision_set"])
                    break
        persons.append({
            "entity_key": key,
            "value": value,
            "surname_key": surname_key,
            "surname_tier": tier,
            "in_top1000": surname_key in pools["top1000_set"],
            "in_extended_only": surname_key in pools["extended_all"] and surname_key not in pools["top1000_set"],
            "is_collision_candidate": surname_key in pools["collision_set"],
        })
    return persons


def generate_stress_dataset(
    count: int,
    out_dir: str,
    seed: int | None = None,
    scope: str = "business",
) -> None:
    assets = _repo_root() / "app" / "src" / "main" / "assets"
    pools = _build_pools(assets)
    _install_stress_gen_person(pools)
    _install_stress_gen_address(pools)

    out_path = Path(out_dir)
    docs_path = out_path / "docs"
    docs_path.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed)

    business_types = list(gen._BUILDERS.keys()) + ["skarga"]
    prose_types = [t for t in gclean._PROSE_BUILDERS if t in gclean.FROZEN_PROSE_TYPES]
    all_types = business_types if scope == "business" else business_types + prose_types

    ground_truth: list[dict[str, Any]] = []
    tier_counts: dict[str, int] = {}

    print("\nGenerator STRESS — duże pule słownikowe")
    print("-" * 52)
    print(f"  Dokumenty      : {count}")
    print(f"  Imiona (pula)  : {len(pools['male_names'])} M + {len(pools['female_names'])} F")
    print(f"  Nazwiska 39k   : {len(pools['extended_all'])}")
    print(f"  Tylko extended : {len(pools['extended_only'])}")
    print(f"  Kolizje        : {len(pools['collision'])} ({', '.join(pools['collision'][:8])}…)")
    print(f"  Ulice (pula)   : {len(pools['street_keys'])}")
    print(f"  Miasta (pula)  : {len(pools['cities'])}")
    print(f"  Wyjście        : {out_path.resolve()}")
    print(f"  Ziarno         : {seed if seed is not None else 'losowe'}")
    print()

    for i in range(count):
        doc_type = rng.choice(all_types)
        if doc_type in gclean._PROSE_BUILDERS:
            text, entities = gclean._PROSE_BUILDERS[doc_type](rng)
        else:
            text, entities = gclean.build_business_as_text(doc_type, rng)

        fname = f"doc_{i:05d}.txt"
        (docs_path / fname).write_text(text, encoding="utf-8")

        stress_persons = _extract_stress_from_entities(entities, pools)
        for p in stress_persons:
            tier_counts[p["surname_tier"]] = tier_counts.get(p["surname_tier"], 0) + 1

        ground_truth.append({
            "file": f"docs/{fname}",
            "doc_type": doc_type,
            "entities": entities,
            "stress": {
                "persons": stress_persons,
                "pool_sizes": {
                    "names_m": len(pools["male_names"]),
                    "names_f": len(pools["female_names"]),
                    "surnames_extended": len(pools["extended_all"]),
                    "surnames_top1000": len(pools["top1000"]),
                    "surnames_extended_only": len(pools["extended_only"]),
                    "collision_candidates": len(pools["collision"]),
                    "streets": len(pools["street_keys"]),
                    "cities": len(pools["cities"]),
                },
            },
        })

        if (i + 1) % max(1, count // 20) == 0 or i == count - 1:
            print(f"\r  {i + 1}/{count}", end="", flush=True)

    print()
    print(f"  Rozkład tierów nazwisk w GT: {tier_counts}")

    meta = {
        "generator": "generator_stress.py",
        "seed": seed,
        "count": count,
        "scope": scope,
        "tier_counts": tier_counts,
        "pool_sizes": ground_truth[0]["stress"]["pool_sizes"] if ground_truth else {},
    }
    (out_path / "stress_meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (out_path / "ground_truth.json").write_text(
        json.dumps(ground_truth, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"  Zapisano: {out_path / 'ground_truth.json'}")
    print(f"  Zapisano: {out_path / 'stress_meta.json'}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generator benchmarku stress (duże pule OSOBA)")
    parser.add_argument("--count", type=int, default=300, help="Liczba dokumentów (domyślnie 300)")
    parser.add_argument("--output", type=str, default="dataset_stress", help="Katalog wyjściowy")
    parser.add_argument("--seed", type=int, default=42, help="Ziarno RNG (powtarzalność)")
    parser.add_argument("--scope", choices=("business", "all"), default="business")
    parser.add_argument("--quick", action="store_true", help="Skrót: 50 dokumentów, seed 1")
    args = parser.parse_args()
    if args.quick:
        args.count = 50
        args.seed = 1
    generate_stress_dataset(args.count, args.output, args.seed, args.scope)


if __name__ == "__main__":
    main()
