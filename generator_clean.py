#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generator_clean.py — Generator "czystych" dokumentów (DOCX/XLSX/TXT — bez OCR)
=================================================================================
Etap 1 planu "benchmark czysty" (11.07.2026). DOCX/XLSX/TXT nie przechodzą przez OCR
w LynxMask Mobile (sprawdzone w IncomingDocumentFlow.kt — bezpośredni odczyt pliku),
więc degradacja obrazu (blur/szum/rotacja) używana w benchmarku fresh/stały jest dla
tych formatów bez sensu. Ten generator NIE tworzy obrazów — tylko tekst + ground truth.

Reużywa generatory encji (gen_pesel, gen_nip, gen_iban, gen_address, adresy/miasta/ulice)
i istniejące szablony biznesowe (umowa/faktura/decyzja itd.) z generator.py — zero
duplikacji. Dokłada NOWE szablony prozy (esej/list/skarga/wspomnienie), gdzie PII jest
wplecione w naturalne, ODMIENIONE zdania po polsku, nie w polach "etykieta: wartość"
— to dokładnie klasa dokumentów, w której diagnoza eseju SETI (09.07) znalazła bugi,
jakich formularze nigdy nie ujawniają.

Użycie:
  python generator_clean.py --count 100 --output dataset_clean
  python generator_clean.py --count 50  --output quick_clean --seed 42

Wyjście:
  <output>/docs/doc_00000.txt, doc_00001.txt, ...
  <output>/ground_truth.json   — [{file, doc_type, entities}, ...]
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
from typing import Any

import generator as gen  # reużywa gen_pesel/gen_nip/gen_iban/gen_address/... + pule danych + _BUILDERS

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 1 — Deklinacja imion i nazwisk (tabela ręczna — pula w generator.py jest
# zamknięta i mała, 24-25 pozycji na listę, więc ręczne tabele są DOKŁADNE, nie
# przybliżone regułą. Rozszerzenie puli w generator.py wymaga dopisania tu form.)
# ─────────────────────────────────────────────────────────────────────────────

# Imiona męskie — wzorzec regularny (+a/+owi/+em) dla całej puli _M_FIRST
_GEN_M_FIRST = {
    "Adam": "Adama", "Piotr": "Piotra", "Tomasz": "Tomasza", "Marek": "Marka",
    "Andrzej": "Andrzeja", "Jan": "Jana", "Michał": "Michała", "Krzysztof": "Krzysztofa",
    "Paweł": "Pawła", "Marcin": "Marcina", "Łukasz": "Łukasza", "Jakub": "Jakuba",
    "Mateusz": "Mateusza", "Bartosz": "Bartosza", "Rafał": "Rafała", "Grzegorz": "Grzegorza",
    "Dariusz": "Dariusza", "Mariusz": "Mariusza", "Sławomir": "Sławomira",
    "Wojciech": "Wojciecha", "Zbigniew": "Zbigniewa", "Tadeusz": "Tadeusza",
    "Robert": "Roberta", "Kamil": "Kamila", "Maciej": "Macieja",
}
_INSTR_M_FIRST = {
    "Adam": "Adamem", "Piotr": "Piotrem", "Tomasz": "Tomaszem", "Marek": "Markiem",
    "Andrzej": "Andrzejem", "Jan": "Janem", "Michał": "Michałem", "Krzysztof": "Krzysztofem",
    "Paweł": "Pawłem", "Marcin": "Marcinem", "Łukasz": "Łukaszem", "Jakub": "Jakubem",
    "Mateusz": "Mateuszem", "Bartosz": "Bartoszem", "Rafał": "Rafałem", "Grzegorz": "Grzegorzem",
    "Dariusz": "Dariuszem", "Mariusz": "Mariuszem", "Sławomir": "Sławomirem",
    "Wojciech": "Wojciechem", "Zbigniew": "Zbigniewem", "Tadeusz": "Tadeuszem",
    "Robert": "Robertem", "Kamil": "Kamilem", "Maciej": "Maciejem",
}
_DAT_M_FIRST = {
    "Adam": "Adamowi", "Piotr": "Piotrowi", "Tomasz": "Tomaszowi", "Marek": "Markowi",
    "Andrzej": "Andrzejowi", "Jan": "Janowi", "Michał": "Michałowi", "Krzysztof": "Krzysztofowi",
    "Paweł": "Pawłowi", "Marcin": "Marcinowi", "Łukasz": "Łukaszowi", "Jakub": "Jakubowi",
    "Mateusz": "Mateuszowi", "Bartosz": "Bartoszowi", "Rafał": "Rafałowi", "Grzegorz": "Grzegorzowi",
    "Dariusz": "Dariuszowi", "Mariusz": "Mariuszowi", "Sławomir": "Sławomirowi",
    "Wojciech": "Wojciechowi", "Zbigniew": "Zbigniewowi", "Tadeusz": "Tadeuszowi",
    "Robert": "Robertowi", "Kamil": "Kamilowi", "Maciej": "Maciejowi",
}

# Imiona żeńskie — wzorzec regularny (+y|+ii / +ą) dla całej puli _F_FIRST
_GEN_F_FIRST = {
    "Anna": "Anny", "Maria": "Marii", "Katarzyna": "Katarzyny", "Agnieszka": "Agnieszki",
    "Barbara": "Barbary", "Ewa": "Ewy", "Małgorzata": "Małgorzaty", "Joanna": "Joanny",
    "Monika": "Moniki", "Beata": "Beaty", "Dorota": "Doroty", "Marta": "Marty",
    "Aleksandra": "Aleksandry", "Natalia": "Natalii", "Karolina": "Karoliny",
    "Zofia": "Zofii", "Elżbieta": "Elżbiety", "Iwona": "Iwony", "Renata": "Renaty",
    "Sylwia": "Sylwii", "Magdalena": "Magdaleny", "Justyna": "Justyny",
    "Paulina": "Pauliny", "Edyta": "Edyty",
}
_INSTR_F_FIRST = {
    "Anna": "Anną", "Maria": "Marią", "Katarzyna": "Katarzyną", "Agnieszka": "Agnieszką",
    "Barbara": "Barbarą", "Ewa": "Ewą", "Małgorzata": "Małgorzatą", "Joanna": "Joanną",
    "Monika": "Moniką", "Beata": "Beatą", "Dorota": "Dorotą", "Marta": "Martą",
    "Aleksandra": "Aleksandrą", "Natalia": "Natalią", "Karolina": "Karoliną",
    "Zofia": "Zofią", "Elżbieta": "Elżbietą", "Iwona": "Iwoną", "Renata": "Renatą",
    "Sylwia": "Sylwią", "Magdalena": "Magdaleną", "Justyna": "Justyną",
    "Paulina": "Pauliną", "Edyta": "Edytą",
}

# Nazwiska męskie — dwie klasy: przymiotnikowe (-ski/-cki) i rzeczownikowe (reszta)
_M_LAST_SKI = {
    "Kowalski", "Wiśniewski", "Kamiński", "Lewandowski", "Szymański", "Dąbrowski",
    "Kozłowski", "Jankowski", "Kwiatkowski", "Piotrowski", "Grabowski",
    "Nowakowski", "Pawłowski", "Michalski", "Jabłoński",
}
_GEN_M_LAST_REGULAR = {
    "Nowak": "Nowaka", "Wójcik": "Wójcika", "Kowalczyk": "Kowalczyka",
    "Woźniak": "Woźniaka", "Mazur": "Mazura", "Krawczyk": "Krawczyka",
    "Adamczyk": "Adamczyka", "Dudek": "Dudka", "Zając": "Zająca", "Wieczorek": "Wieczorka",
}
_INSTR_M_LAST_REGULAR = {
    "Nowak": "Nowakiem", "Wójcik": "Wójcikiem", "Kowalczyk": "Kowalczykiem",
    "Woźniak": "Woźniakiem", "Mazur": "Mazurem", "Krawczyk": "Krawczykiem",
    "Adamczyk": "Adamczykiem", "Dudek": "Dudkiem", "Zając": "Zającem", "Wieczorek": "Wieczorkiem",
}
_DAT_M_LAST_REGULAR = {
    "Nowak": "Nowakowi", "Wójcik": "Wójcikowi", "Kowalczyk": "Kowalczykowi",
    "Woźniak": "Woźniakowi", "Mazur": "Mazurowi", "Krawczyk": "Krawczykowi",
    "Adamczyk": "Adamczykowi", "Dudek": "Dudkowi", "Zając": "Zającowi", "Wieczorek": "Wieczorkowi",
}

# Nazwiska żeńskie — -ska/-cka deklinuje się jak przymiotnik; reszta (Nowak, Wójcik,
# Kowalczyk, Woźniak, Mazur, Krawczyk, Adamczyk, Dudek, Zając, Wieczorek) jest
# NIEODMIENNA dla kobiet (ważny fakt gramatyczny — realny test: samo imię się odmienia,
# nazwisko zostaje w mianowniku, np. "z Anną Nowak", nie "z Anną Nowakiem").
_F_LAST_SKA = {
    "Kowalska", "Wiśniewska", "Kamińska", "Lewandowska", "Szymańska", "Dąbrowska",
    "Kozłowska", "Jankowska", "Kwiatkowska", "Piotrowska", "Grabowska",
    "Nowakowska", "Pawłowska", "Michalska", "Jabłońska",
}


def _decline_first(first: str, male: bool, case: str) -> str:
    """case: 'gen' | 'instr' | 'dat' | 'nom'"""
    if case == "nom":
        return first
    table = {
        ("gen", True): _GEN_M_FIRST, ("instr", True): _INSTR_M_FIRST, ("dat", True): _DAT_M_FIRST,
        ("gen", False): _GEN_F_FIRST, ("instr", False): _INSTR_F_FIRST,
    }.get((case, male))
    if table is None:
        return first
    return table.get(first, first)


def _decline_last(last: str, male: bool, case: str) -> str:
    if case == "nom":
        return last
    if male:
        if last in _M_LAST_SKI:
            stem = last[:-2]  # "...ski"/"...cki" -> ubierz "ski"/"cki"
            return {"gen": stem + "kiego", "instr": stem + "kim", "dat": stem + "kiemu"}.get(case, last) \
                if last.endswith("ski") else \
                {"gen": stem + "ciego", "instr": stem + "cim", "dat": stem + "ciemu"}.get(case, last)
        table = {"gen": _GEN_M_LAST_REGULAR, "instr": _INSTR_M_LAST_REGULAR, "dat": _DAT_M_LAST_REGULAR}.get(case)
        return table.get(last, last) if table else last
    else:
        if last in _F_LAST_SKA:
            stem = last[:-2]  # "...ska"/"...cka" -> ubierz "ska"/"cka"
            if case == "gen":
                return stem + ("kiej" if last.endswith("ska") else "ciej")
            if case == "instr":
                return stem + ("ką" if last.endswith("ska") else "cką")
            return last
        # nieodmienne nazwiska żeńskie (Nowak, Wójcik, Mazur, ...)
        return last


# Miejscownik miast ("w Krakowie", nie "w Kraków") — cała pula _CITIES z generator.py.
_LOC_CITY = {
    "Warszawa": "Warszawie", "Kraków": "Krakowie", "Łódź": "Łodzi", "Wrocław": "Wrocławiu",
    "Poznań": "Poznaniu", "Gdańsk": "Gdańsku", "Szczecin": "Szczecinie",
    "Bydgoszcz": "Bydgoszczy", "Lublin": "Lublinie", "Białystok": "Białymstoku",
    "Katowice": "Katowicach", "Gdynia": "Gdyni", "Częstochowa": "Częstochowie",
    "Radom": "Radomiu", "Sosnowiec": "Sosnowcu", "Toruń": "Toruniu", "Kielce": "Kielcach",
    "Rzeszów": "Rzeszowie", "Gliwice": "Gliwicach", "Zabrze": "Zabrzu",
    "Olsztyn": "Olsztynie", "Bielsko-Biała": "Bielsku-Białej", "Bytom": "Bytomiu",
    "Zielona Góra": "Zielonej Górze", "Rybnik": "Rybniku",
}


def loc_city(city: str) -> str:
    return _LOC_CITY.get(city, city)


class Person:
    """Osoba z pełną odmianą imienia+nazwiska (mianownik/dopełniacz/celownik/narzędnik)."""

    def __init__(self, rng: random.Random):
        self.male = rng.random() < 0.5
        self.first = rng.choice(gen._M_FIRST if self.male else gen._F_FIRST)
        self.last = rng.choice(gen._M_LAST if self.male else gen._F_LAST)

    def form(self, case: str) -> str:
        return f"{_decline_first(self.first, self.male, case)} {_decline_last(self.last, self.male, case)}"

    @property
    def nom(self) -> str: return self.form("nom")
    @property
    def gen(self) -> str: return self.form("gen")
    @property
    def dat(self) -> str: return self.form("dat")
    @property
    def instr(self) -> str: return self.form("instr")


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 2 — Szablony prozy (PII wplecione w naturalne zdania, nie pola etykieta:wartość)
# ─────────────────────────────────────────────────────────────────────────────

def build_esej(rng: random.Random) -> tuple[str, dict[str, Any]]:
    author = Person(rng)
    mentor = Person(rng)
    addr = gen.gen_address(rng)
    phone = gen.gen_phone(rng)
    email = gen.gen_email(rng, {"first": author.first, "last": author.last})
    pesel = gen.gen_pesel(rng)

    text = (
        f"Nazywam się {author.nom} i chciałbym/chciałabym opowiedzieć o swojej drodze "
        f"zawodowej. Urodziłem/-am się i wychowałem/-am w {loc_city(addr['city'])}, gdzie do dziś "
        f"mieszkam przy {addr['street']} {addr['house']}. Moim opiekunem naukowym przez "
        f"cały okres studiów był {mentor.nom} — to właśnie dzięki {mentor.dat} "
        f"zdecydowałem/-am się kontynuować pracę badawczą.\n\n"
        f"Pamiętam pierwsze spotkanie z {mentor.instr} — rozmawialiśmy o kierunku, jaki "
        f"powinna obrać moja praca. Kilka miesięcy później napisałem/napisałam do "
        f"{mentor.gen} list z prośbą o rekomendację, podając swój numer kontaktowy "
        f"{phone} oraz adres {email}. W dokumentach aplikacyjnych musiałem/musiałam podać "
        f"również numer PESEL: {pesel}.\n\n"
        f"Dziś, patrząc wstecz, jestem wdzięczny/wdzięczna losowi za spotkanie z "
        f"{mentor.instr}. To doświadczenie ukształtowało sposób, w jaki podchodzę do "
        f"pracy i do ludzi."
    )
    entities = {
        "autor": author.nom, "mentor": mentor.nom,
        "adres": f"{addr['street']} {addr['house']}, {addr['postcode']} {addr['city']}",
        "telefon": phone, "email": email, "pesel": pesel,
    }
    return text, entities


def build_list_osobisty(rng: random.Random) -> tuple[str, dict[str, Any]]:
    sender = Person(rng)
    recipient = Person(rng)
    mediator = Person(rng)
    addr = gen.gen_address(rng)
    phone = gen.gen_phone(rng)

    text = (
        f"Droga/Drogi {recipient.nom.split()[0]},\n\n"
        f"piszę do Ciebie po dłuższej przerwie, bo w końcu udało mi się załatwić sprawę, "
        f"o którą prosiłaś/prosiłeś. Rozmawiałem/rozmawiałam z {mediator.instr} "
        f"z urzędu i wszystko powinno już być w porządku.\n\n"
        f"Możesz do mnie pisać na dawny adres, {addr['street']} {addr['house']}, "
        f"{addr['postcode']} {addr['city']}, albo zadzwonić pod {phone}. Pozdrów ode mnie "
        f"{recipient.gen} rodzinę, a jakbyś miał/miała chwilę, zadzwoń do {recipient.gen}, "
        f"bo dawno nie rozmawialiśmy.\n\n"
        f"Ściskam,\n{sender.nom}"
    )
    entities = {
        "nadawca": sender.nom, "odbiorca": recipient.nom, "urzednik": mediator.nom,
        "adres": f"{addr['street']} {addr['house']}, {addr['postcode']} {addr['city']}",
        "telefon": phone,
    }
    return text, entities


def build_skarga(rng: random.Random) -> tuple[str, dict[str, Any]]:
    complainant = Person(rng)
    accused = Person(rng)
    addr = gen.gen_address(rng)
    doc_date = gen.gen_doc_date(rng)
    pesel = gen.gen_pesel(rng)
    phone = gen.gen_phone(rng)

    text = (
        f"{addr['city']}, dnia {doc_date}\n\n"
        f"SKARGA\n\n"
        f"Ja, niżej podpisany/podpisana {complainant.nom}, zamieszkały/zamieszkała przy "
        f"{addr['street']} {addr['house']} w {loc_city(addr['city'])}, PESEL {pesel}, składam "
        f"niniejszą skargę na działania {accused.gen}.\n\n"
        f"W dniu {doc_date} {accused.nom} dopuścił/dopuściła się zachowania, które uważam "
        f"za rażące naruszenie moich praw. Rozmawiałem/rozmawiałam z {accused.instr} "
        f"osobiście, próbując wyjaśnić sytuację polubownie, jednak bezskutecznie.\n\n"
        f"Proszę o zbadanie sprawy i podjęcie odpowiednich kroków wobec {accused.gen}. "
        f"W razie potrzeby jestem dostępny/dostępna pod numerem {phone}.\n\n"
        f"Z poważaniem,\n{complainant.nom}"
    )
    entities = {
        "skarzacy": complainant.nom, "oskarzony": accused.nom,
        "adres": f"{addr['street']} {addr['house']}, {addr['city']}",
        "data": doc_date, "pesel": pesel, "telefon": phone,
    }
    return text, entities


def build_wspomnienie(rng: random.Random) -> tuple[str, dict[str, Any]]:
    narrator = Person(rng)
    friend = Person(rng)
    addr = gen.gen_address(rng)
    birth = gen.gen_birth_date(rng)

    text = (
        f"Miałem/miałam wtedy kilkanaście lat, kiedy poznałem/poznałam {friend.gen} — "
        f"było to w {loc_city(addr['city'])}, na {addr['street']}, gdzie mieszkała moja "
        f"rodzina. Urodziłem/-am się {birth} i całe dzieciństwo spędziłem/spędziłam w tej "
        f"okolicy.\n\n"
        f"Z {friend.instr} przyjaźniliśmy się przez wiele lat. Pamiętam, jak "
        f"{narrator.nom} razem z {friend.instr} chodziliśmy tą samą drogą do szkoły, "
        f"nieświadomi, jak bardzo to miejsce zapadnie nam w pamięć.\n\n"
        f"Dziś, kiedy wracam myślami do {friend.gen}, uśmiecham się — tamte lata, mimo "
        f"trudności, były jednymi z najszczęśliwszych w moim życiu."
    )
    entities = {
        "narrator": narrator.nom, "przyjaciel": friend.nom,
        "adres": f"{addr['street']}, {addr['city']}", "data_urodzenia": birth,
    }
    return text, entities


_PROSE_BUILDERS = {
    "esej": build_esej,
    "list_osobisty": build_list_osobisty,
    "skarga": build_skarga,
    "wspomnienie": build_wspomnienie,
}

# DECYZJA ZAKRESU (12.07): silnik celuje w dokumenty formalne/urzędowe/biznesowe (kontekst
# RODO), nie w wolną prozę/dokumenty prywatne — patrz TODO.md "DECYZJA ZAKRESU 12.07".
# "skarga" strukturalnie jest formalnym pismem do urzędu (nagłówek, data+miasto, podpis,
# żądanie działania) — ten sam rodzaj dokumentu co wezwanie/pismo_urzedowe, NIE osobista
# narracja jak esej/list_osobisty/wspomnienie — więc liczy się jako "biznes", mimo że jej
# builder mieszka technicznie w _PROSE_BUILDERS (bo to wolny tekst, nie pola etykieta:wartość).
FROZEN_PROSE_TYPES = {"esej", "list_osobisty", "wspomnienie"}


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 3 — Spłaszczenie istniejących szablonów biznesowych (Block -> tekst)
# Reużywa TE SAME buildery co generator.py (umowa/faktura/decyzja/...) — zero
# duplikacji, pełne pokrycie typów encji które one już testują (NIP/IBAN/PESEL/
# KWOTA/ADRES/numer faktury/sygnatura...), tylko bez renderowania do PNG.
# ─────────────────────────────────────────────────────────────────────────────

def blocks_to_text(blocks: list) -> str:
    lines: list[str] = []
    for b in blocks:
        if b.style == "spacer":
            lines.append("")
        elif b.style == "separator":
            lines.append("-" * 40)
        else:
            lines.append(b.text)
    return "\n".join(lines)


def build_business_as_text(doc_type: str, rng: random.Random) -> tuple[str, dict[str, Any]]:
    blocks, entities = gen._BUILDERS[doc_type](rng)
    return blocks_to_text(blocks), entities


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 4 — Generowanie datasetu
# ─────────────────────────────────────────────────────────────────────────────

def generate_clean_dataset(count: int, out_dir: str, seed: int | None = None, scope: str = "business") -> None:
    out_path = Path(out_dir)
    docs_path = out_path / "docs"
    docs_path.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed)

    business_types = list(gen._BUILDERS.keys()) + ["skarga"]  # skarga = pismo formalne, patrz FROZEN_PROSE_TYPES
    prose_types = [t for t in _PROSE_BUILDERS if t in FROZEN_PROSE_TYPES]
    all_types = business_types if scope == "business" else business_types + prose_types

    ground_truth: list[dict[str, Any]] = []

    print(f"\nGenerator czysty (bez OCR) — dokumenty tekstowe DOCX/XLSX/TXT")
    print(f"{'-'*52}")
    print(f"  Dokumenty : {count}")
    print(f"  Wyjście   : {out_path.resolve()}")
    print(f"  Ziarno    : {seed if seed is not None else 'losowe (świeże za każdym razem)'}")
    print(f"  Zakres    : {scope} ({len(all_types)} typów)")
    print()

    for i in range(count):
        doc_type = rng.choice(all_types)
        if doc_type in _PROSE_BUILDERS:
            text, entities = _PROSE_BUILDERS[doc_type](rng)
        else:
            text, entities = build_business_as_text(doc_type, rng)

        fname = f"doc_{i:05d}.txt"
        (docs_path / fname).write_text(text, encoding="utf-8")

        ground_truth.append({
            "file": f"docs/{fname}",
            "doc_type": doc_type,
            "entities": entities,
        })

        if (i + 1) % max(1, count // 20) == 0 or i == count - 1:
            print(f"\r  {i+1}/{count}", end="", flush=True)

    print()

    gt_path = out_path / "ground_truth.json"
    gt_path.write_text(json.dumps(ground_truth, ensure_ascii=False, indent=2), encoding="utf-8")

    type_counts: dict[str, int] = {}
    for e in ground_truth:
        type_counts[e["doc_type"]] = type_counts.get(e["doc_type"], 0) + 1
    print(f"\n  OK Wygenerowano {count} dokumentów -> {docs_path}")
    print(f"  OK Ground truth -> {gt_path}")
    print(f"\n  Rozklad typow:")
    for dt, cnt in sorted(type_counts.items(), key=lambda x: -x[1]):
        tag = "proza" if dt in FROZEN_PROSE_TYPES else "biznes"
        print(f"    [{tag:6s}] {dt:<28} {cnt:4d}  ({cnt/count*100:4.1f}%)")
    print()


def main() -> None:
    p = argparse.ArgumentParser(description="Generator czystych dokumentów TXT (bez OCR) dla LynxMask")
    p.add_argument("--count", type=int, default=100)
    p.add_argument("--output", type=str, default="dataset_clean")
    p.add_argument("--seed", type=int, default=None)
    p.add_argument("--scope", type=str, choices=["business", "all"], default="business",
                    help="business = tylko dokumenty formalne/RODO (domyślnie, decyzja 12.07); "
                         "all = dokłada zamrożone szablony wolnej prozy (esej/list_osobisty/wspomnienie)")
    args = p.parse_args()
    generate_clean_dataset(args.count, args.output, args.seed, args.scope)


if __name__ == "__main__":
    main()
