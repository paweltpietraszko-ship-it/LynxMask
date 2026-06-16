#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OCR Dataset Generator — Synthetic Polish Documents
====================================================
Generates synthetic Polish document images (PNG) with ground truth JSON.
Designed for testing pseudonymization / PII-masking pipelines.

Dependencies: Pillow, NumPy

Usage:
    python generator.py --count 1000 --output dataset
    python generator.py --count 500  --output test_set --seed 42
    python generator.py --count 100  --output quick    --no-balance
"""
from __future__ import annotations

import argparse
import io
import json
import math
import os
import random
import sys
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 1 — Canvas & style constants
# ─────────────────────────────────────────────────────────────────────────────

A4_W: int = 1240          # A4 @ 150 dpi
A4_H: int = 1754
MARGIN: int = 92
LINE_GAP: int = 7         # extra pixels between lines

STYLE: dict[str, dict] = {
    "title":     {"size": 19, "bold": True,  "lh": 30, "color": (0, 0, 0)},
    "heading":   {"size": 16, "bold": True,  "lh": 26, "color": (0, 0, 0)},
    "body":      {"size": 13, "bold": False, "lh": 22, "color": (15, 15, 15)},
    "label":     {"size": 13, "bold": True,  "lh": 22, "color": (0, 0, 0)},
    "small":     {"size": 10, "bold": False, "lh": 17, "color": (90, 90, 90)},
    "separator": {"size": 0,  "bold": False, "lh": 14, "color": (0, 0, 0)},
    "spacer":    {"size": 0,  "bold": False, "lh": 10, "color": (0, 0, 0)},
    "mono":      {"size": 12, "bold": False, "lh": 21, "color": (10, 10, 10)},
}

# Pillow API compatibility shim
try:
    _BICUBIC    = Image.Resampling.BICUBIC
    _NEAREST    = Image.Resampling.NEAREST
    _PERSPECTIVE = Image.Transform.PERSPECTIVE
except AttributeError:
    _BICUBIC    = Image.BICUBIC      # type: ignore[attr-defined]
    _NEAREST    = Image.NEAREST      # type: ignore[attr-defined]
    _PERSPECTIVE = Image.PERSPECTIVE  # type: ignore[attr-defined]

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 2 — Font loader
# ─────────────────────────────────────────────────────────────────────────────

_FONT_SEARCH = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/Library/Fonts/Arial.ttf",
    "C:/Windows/Fonts/arial.ttf",
]

_font_regular: str | None = None
_font_bold: str | None = None
_font_mono: str | None = None
_use_ttf: bool = False
_font_cache: dict[tuple[int, bool, bool], ImageFont.FreeTypeFont] = {}


def _init_fonts() -> None:
    global _font_regular, _font_bold, _font_mono, _use_ttf
    for p in _FONT_SEARCH:
        if not os.path.isfile(p):
            continue
        low = os.path.basename(p).lower()
        if "mono" in low and _font_mono is None:
            _font_mono = p
        elif ("bold" in low or "-b." in low or "bd" in low) and _font_bold is None:
            _font_bold = p
        elif _font_regular is None:
            _font_regular = p
    if _font_bold is None:
        _font_bold = _font_regular
    if _font_mono is None:
        _font_mono = _font_regular
    _use_ttf = _font_regular is not None


def get_font(size: int, bold: bool = False, mono: bool = False) -> ImageFont.ImageFont:
    key = (size, bold, mono)
    if key in _font_cache:
        return _font_cache[key]
    if _use_ttf:
        path = (_font_mono if mono else (_font_bold if bold else _font_regular))
        assert path is not None
        try:
            f = ImageFont.truetype(path, size)
            _font_cache[key] = f
            return f
        except Exception:
            pass
    f = ImageFont.load_default()
    _font_cache[key] = f
    return f


def _tr(text: str) -> str:
    """Transliterate Polish diacritics → ASCII when no TTF font available."""
    if _use_ttf:
        return text
    return text.translate(str.maketrans(
        "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ",
        "acelnoszzACELNOSZZ"
    ))


_init_fonts()

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 3 — Polish data pools
# ─────────────────────────────────────────────────────────────────────────────

_M_FIRST = [
    "Adam", "Piotr", "Tomasz", "Marek", "Andrzej", "Jan", "Michał",
    "Krzysztof", "Paweł", "Marcin", "Łukasz", "Jakub", "Mateusz",
    "Bartosz", "Rafał", "Grzegorz", "Dariusz", "Mariusz", "Sławomir",
    "Wojciech", "Zbigniew", "Tadeusz", "Robert", "Kamil", "Maciej",
]
_F_FIRST = [
    "Anna", "Maria", "Katarzyna", "Agnieszka", "Barbara", "Ewa",
    "Małgorzata", "Joanna", "Monika", "Beata", "Dorota", "Marta",
    "Aleksandra", "Natalia", "Karolina", "Zofia", "Elżbieta", "Iwona",
    "Renata", "Sylwia", "Magdalena", "Justyna", "Paulina", "Edyta",
]
_M_LAST = [
    "Nowak", "Kowalski", "Wiśniewski", "Wójcik", "Kowalczyk",
    "Kamiński", "Lewandowski", "Szymański", "Woźniak", "Dąbrowski",
    "Kozłowski", "Jankowski", "Mazur", "Kwiatkowski", "Krawczyk",
    "Piotrowski", "Grabowski", "Nowakowski", "Pawłowski", "Michalski",
    "Adamczyk", "Dudek", "Zając", "Wieczorek", "Jabłoński",
]
_F_LAST = [
    "Nowak", "Kowalska", "Wiśniewska", "Wójcik", "Kowalczyk",
    "Kamińska", "Lewandowska", "Szymańska", "Woźniak", "Dąbrowska",
    "Kozłowska", "Jankowska", "Mazur", "Kwiatkowska", "Krawczyk",
    "Piotrowska", "Grabowska", "Nowakowska", "Pawłowska", "Michalska",
    "Adamczyk", "Dudek", "Zając", "Wieczorek", "Jabłońska",
]
_CITIES = [
    "Warszawa", "Kraków", "Łódź", "Wrocław", "Poznań", "Gdańsk",
    "Szczecin", "Bydgoszcz", "Lublin", "Białystok", "Katowice",
    "Gdynia", "Częstochowa", "Radom", "Sosnowiec", "Toruń",
    "Kielce", "Rzeszów", "Gliwice", "Zabrze", "Olsztyn",
    "Bielsko-Biała", "Bytom", "Zielona Góra", "Rybnik",
]
_POSTCODES = [
    "00-001", "01-100", "02-200", "03-300", "10-100",
    "20-100", "30-001", "40-001", "50-001", "60-001",
    "70-001", "80-001", "85-001", "90-001", "41-200",
    "31-001", "44-100", "45-001", "65-001", "87-100",
]
_STREETS = [
    "ul. Kwiatowa", "ul. Leśna", "ul. Słoneczna", "ul. Kościuszki",
    "ul. Mickiewicza", "ul. Lipowa", "ul. Polna", "ul. Ogrodowa",
    "ul. Nowa", "ul. Długa", "al. Jana Pawła II", "ul. Piaskowa",
    "ul. Wiosenna", "ul. Różana", "ul. Dębowa", "pl. Wolności",
    "ul. Warszawska", "ul. Krakowska", "ul. Szkolna", "ul. Kolejowa",
    "ul. Kopernika", "ul. Sienkiewicza", "ul. Chopina", "ul. Norwida",
    "al. Niepodległości", "ul. Wolności", "ul. Sportowa", "ul. Zielona",
]
_COMPANIES = [
    "ALFA Sp. z o.o.", "BETA S.A.", "GAMMA Sp. z o.o. S.K.A.",
    "Usługi Informatyczne Jan Kowalski", "Handel i Usługi Sp. z o.o.",
    "Przedsiębiorstwo Budowlane MARBUD S.A.",
    "Firma Logistyczna TRANS Sp. z o.o.",
    "Biuro Rachunkowe RACHMISTRZ", "Kancelaria Prawna LEGIS S.C.",
    "Centrum Medyczne MEDYK Sp. z o.o.", "Agencja Reklamowa KREACJA",
    "Hurtownia Materiałów BUDMAT Sp. z o.o.",
    "Serwis AGD ELEKTRO Sp. z o.o.", "Zakład Ubezpieczeń POLISA S.A.",
    "Doradztwo Biznesowe EXPERT Sp. z o.o.",
]
_ADMIN_BODIES = [
    "Urząd Skarbowy w Warszawie",
    "Urząd Skarbowy Kraków-Śródmieście",
    "Zakład Ubezpieczeń Społecznych Oddział w Warszawie",
    "Urząd Miejski w Krakowie, Wydział Spraw Administracyjnych",
    "Starostwo Powiatowe w Radomiu",
    "Urząd Marszałkowski Województwa Mazowieckiego",
    "Urząd Gminy Warszawa-Wola",
    "Urząd Celno-Skarbowy w Gdańsku",
    "Powiatowy Urząd Pracy w Poznaniu",
    "Urząd Regulacji Energetyki",
]
_BAILIFFS = [
    "Komornik Sądowy przy Sądzie Rejonowym Anna Zielińska",
    "Komornik Sądowy przy SR Warszawa-Mokotów Tomasz Malinowski",
    "Komornik Sądowy przy SR Kraków-Nowa Huta Piotr Nowacki",
    "Komornik Sądowy przy SR dla Wrocławia-Fabrycznej Marta Kędzierska",
    "Komornik Sądowy przy SR Poznań-Grunwald Janusz Wierzbicki",
]

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 4 — Entity generators
# ─────────────────────────────────────────────────────────────────────────────

def gen_pesel(rng: random.Random) -> str:
    """Generate PESEL with valid checksum (birth 1950-2005)."""
    start = date(1950, 1, 1)
    birth = start + timedelta(days=rng.randint(0, (date(2005, 12, 31) - start).days))
    y, m, d = birth.year, birth.month, birth.day

    # Encode century in month
    if 1900 <= y <= 1999:
        mm = m
    elif 2000 <= y <= 2099:
        mm = m + 20
    elif 1800 <= y <= 1899:
        mm = m + 80
    else:
        mm = m + 40

    yy = y % 100
    serial = rng.randint(0, 999)
    sex_d = rng.choice([1, 3, 5, 7, 9] if rng.random() < 0.5 else [0, 2, 4, 6, 8])

    digs = [yy // 10, yy % 10, mm // 10, mm % 10, d // 10, d % 10,
            serial // 100, (serial // 10) % 10, serial % 10, sex_d]
    weights = [1, 3, 7, 9, 1, 3, 7, 9, 1, 3]
    total = sum(w * v for w, v in zip(weights, digs))
    digs.append((10 - total % 10) % 10)
    return "".join(str(x) for x in digs)


def gen_id_card(rng: random.Random) -> str:
    """3 letters + 6 digits (Polish ID format)."""
    letters = "ABCDEFGHIJKLMNOPRSTUWYZ"
    return (rng.choice(letters) + rng.choice(letters) + rng.choice(letters)
            + "".join(str(rng.randint(0, 9)) for _ in range(6)))


def gen_passport(rng: random.Random) -> str:
    """2 letters + 7 digits (Polish passport format)."""
    letters = "ABCDEFGHIJKLMNOPRSTUWYZ"
    return (rng.choice(letters) + rng.choice(letters)
            + "".join(str(rng.randint(0, 9)) for _ in range(7)))


def gen_person(rng: random.Random) -> dict[str, str]:
    male = rng.random() < 0.5
    first = rng.choice(_M_FIRST if male else _F_FIRST)
    last  = rng.choice(_M_LAST  if male else _F_LAST)
    return {"first": first, "last": last, "full": f"{first} {last}"}


def gen_address(rng: random.Random) -> dict[str, str]:
    street = rng.choice(_STREETS)
    house  = str(rng.randint(1, 200))
    if rng.random() < 0.4:
        house += f"/{rng.randint(1, 50)}"
    city     = rng.choice(_CITIES)
    postcode = rng.choice(_POSTCODES)
    return {
        "street": street, "house": house,
        "city": city, "postcode": postcode,
        "full": f"{street} {house}, {postcode} {city}",
    }


def gen_birth_date(rng: random.Random) -> str:
    start = date(1950, 1, 1)
    d = start + timedelta(days=rng.randint(0, (date(2000, 12, 31) - start).days))
    return d.strftime("%d.%m.%Y")


def gen_nip(rng: random.Random) -> str:
    """Generate NIP with valid checksum."""
    for _ in range(100):
        digs = [rng.randint(1, 9)] + [rng.randint(0, 9) for _ in range(8)]
        weights = [6, 5, 7, 2, 3, 4, 5, 6, 7]
        check = sum(w * d for w, d in zip(weights, digs)) % 11
        if check < 10:
            digs.append(check)
            s = "".join(str(x) for x in digs)
            return f"{s[:3]}-{s[3:6]}-{s[6:8]}-{s[8:]}"
    return "123-456-78-90"   # fallback (unreachable in practice)


def gen_regon(rng: random.Random) -> str:
    """Generate 9-digit REGON with valid checksum."""
    digs = [rng.randint(1, 9)] + [rng.randint(0, 9) for _ in range(7)]
    weights = [8, 9, 2, 3, 4, 5, 6, 7]
    check = sum(w * d for w, d in zip(weights, digs)) % 11
    digs.append(0 if check == 10 else check)
    return "".join(str(x) for x in digs)


def gen_iban(rng: random.Random) -> str:
    """Syntactically valid Polish IBAN."""
    bank_codes = ["10901014", "10202892", "15001013",
                  "24010135", "32500003", "11401987", "16901014"]
    bank    = rng.choice(bank_codes)
    account = "".join(str(rng.randint(0, 9)) for _ in range(16))
    bban    = bank + account
    # Check digits: move PL(=2521) to end, replace with 00
    num_str = bban + "252100"
    check   = 98 - (int(num_str) % 97)
    return f"PL{check:02d}{bban}"


def gen_invoice_number(rng: random.Random) -> str:
    year = rng.randint(2020, 2025)
    month = rng.randint(1, 12)
    num = rng.randint(1, 9999)
    return rng.choice([
        f"FV/{year}/{month:02d}/{num:04d}",
        f"FV-{num:05d}/{month:02d}/{year}",
        f"VAT/{year}/{num:04d}",
        f"{num:04d}/{month:02d}/{year}",
    ])


def gen_contract_number(rng: random.Random) -> str:
    year = rng.randint(2018, 2025)
    num  = rng.randint(1, 999)
    return rng.choice([
        f"UMW/{year}/{num:03d}",
        f"KT/{num:04d}/{year}",
        f"U-{num:05d}/{year}",
        f"UMOWA/{year}/NR/{num:04d}",
    ])


def gen_client_number(rng: random.Random) -> str:
    return f"KL-{rng.randint(10000, 99999)}"


def gen_phone(rng: random.Random) -> str:
    prefixes = ["48", "50", "51", "53", "57", "60", "66", "69", "72", "73", "78", "79"]
    p = rng.choice(prefixes) + "".join(str(rng.randint(0, 9)) for _ in range(7))
    return f"+48 {p[:3]} {p[3:6]} {p[6:]}"


def gen_email(rng: random.Random, person: dict[str, str] | None = None) -> str:
    domains = ["gmail.com", "wp.pl", "onet.pl", "interia.pl", "poczta.pl", "o2.pl"]
    domain  = rng.choice(domains)
    if person:
        _strip = str.maketrans("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", "acelnoszzACELNOSZZ")
        first = person["first"].lower().translate(_strip)
        last  = person["last"].lower().translate(_strip)
        sep   = rng.choice([".", "_", ""])
        local = f"{first}{sep}{last}"
        if rng.random() < 0.3:
            local += str(rng.randint(1, 99))
    else:
        chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        local = "".join(rng.choice(chars) for _ in range(rng.randint(6, 12)))
    return f"{local}@{domain}"


def gen_kw_number(rng: random.Random) -> str:
    court_codes = ["WA1M", "KR1P", "KA1K", "WR1K", "PO1P", "GD1G", "LD1M", "BY1B"]
    code  = rng.choice(court_codes)
    num   = rng.randint(10000, 999999)
    check = rng.randint(0, 9)
    return f"{code}/{num:08d}/{check}"


def gen_dzialka_number(rng: random.Random) -> str:
    gmina  = rng.randint(100000, 999999)
    obreb  = rng.randint(1, 99)
    dz     = rng.randint(1, 9999)
    suffix = f"/{rng.randint(1, 20)}" if rng.random() < 0.3 else ""
    return f"{gmina}.{obreb:04d}.{dz}{suffix}"


def gen_sygn_akt(rng: random.Random) -> str:
    types = ["I C", "II C", "I Ns", "II Ns", "I K", "II K", "III K", "I Co"]
    year  = rng.randint(2015, 2025)
    num   = rng.randint(1, 9999)
    return f"{rng.choice(types)} {num}/{year}"


def gen_sygn_komornicza(rng: random.Random) -> str:
    year = rng.randint(2018, 2025)
    num  = rng.randint(100, 99999)
    return f"Km {num}/{year}"


def gen_sygn_admin(rng: random.Random) -> str:
    bodies = ["IW", "OW", "SA", "PD", "KP", "PT"]
    year   = rng.randint(2018, 2025)
    num    = rng.randint(100, 99999)
    return f"{rng.choice(bodies)}.{num:06d}.{year}"


def gen_doc_date(rng: random.Random) -> str:
    start = date(2020, 1, 1)
    d = start + timedelta(days=rng.randint(0, (date(2025, 6, 1) - start).days))
    return d.strftime("%d.%m.%Y")


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 5 — Layout block types
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Block:
    """Single renderable text block."""
    text:   str
    style:  str   = "body"      # key in STYLE dict
    bold:   bool  = False
    indent: int   = 0           # extra left margin in pixels
    align:  str   = "left"      # left | center | right
    mono:   bool  = False


def _sp(n: int = 1)  -> list[Block]: return [Block("", "spacer")  for _ in range(n)]
def _sep()           -> Block:       return Block("", "separator")


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 6 — Document template builders
# ─────────────────────────────────────────────────────────────────────────────

# Return type: (blocks, entities_dict)
DocResult = tuple[list[Block], dict[str, Any]]


def _rows(*pairs: tuple[str, str]) -> list[Block]:
    """Helper: render label / value pairs as indented blocks."""
    out: list[Block] = []
    for label, value in pairs:
        out.append(Block(f"{label}:", "label", bold=True))
        out.append(Block(value, "body", indent=22))
    return out


def _num_words(n: int) -> str:
    """Very rough number → Polish words for amounts (for realistic document text)."""
    k = n // 1000
    r = n % 1000
    if k:
        return f"{k} tysięcy {r}" if r else f"{k} tysięcy"
    return str(n)


# ── 1. Pismo urzędowe ────────────────────────────────────────────────────────
def build_pismo_urzedowe(rng: random.Random) -> DocResult:
    person   = gen_person(rng)
    address  = gen_address(rng)
    sygn     = gen_sygn_akt(rng)
    doc_date = gen_doc_date(rng)
    pesel    = gen_pesel(rng)
    phone    = gen_phone(rng)
    body_org = rng.choice(_ADMIN_BODIES)
    city     = rng.choice(_CITIES)

    entities: dict[str, Any] = {
        "imie_nazwisko": person["full"],
        "adres": address["full"],
        "sygnatura_akt": sygn,
        "pesel": pesel,
        "telefon": phone,
    }
    blocks = [
        Block(body_org, "heading", bold=True),
        *_sp(),
        Block(f"Sygn. akt: {sygn}", "body", bold=True),
        Block(f"{city}, dnia {doc_date}", "body", align="right"),
        *_sp(2),
        Block(person["full"], "body", bold=True),
        Block(f"{address['street']} {address['house']}", "body"),
        Block(f"{address['postcode']} {address['city']}", "body"),
        *_sp(2),
        Block("WEZWANIE DO ZŁOŻENIA WYJAŚNIEŃ", "title", bold=True, align="center"),
        *_sp(),
        Block(
            f"Działając na podstawie art. 155 § 1 Kodeksu postępowania administracyjnego "
            f"(t.j. Dz. U. z 2023 r. poz. 775 ze zm.), {body_org} wzywa Panią/Pana:",
            "body"),
        *_sp(),
        Block(f"{person['full']}, PESEL: {pesel}", "body", bold=True, indent=40),
        *_sp(),
        Block(
            "do złożenia pisemnych wyjaśnień w terminie 14 dni od daty doręczenia "
            "niniejszego pisma w sprawie dotyczącej określenia podstawy wymiaru świadczenia.",
            "body"),
        *_sp(),
        Block("W razie pytań prosimy o kontakt:", "body"),
        Block(f"tel.: {phone}", "body", bold=True),
        *_sp(2),
        Block("Z poważaniem,", "body"),
        *_sp(3),
        Block("................................................", "body"),
        Block("Kierownik Wydziału", "small"),
        Block(body_org, "small"),
        *_sp(),
        _sep(),
        Block(f"Sygn. akt: {sygn}   Wygenerowano elektronicznie.", "small", align="center"),
    ]
    return blocks, entities


# ── 2. Formularz ─────────────────────────────────────────────────────────────
def build_formularz(rng: random.Random) -> DocResult:
    person   = gen_person(rng)
    address  = gen_address(rng)
    pesel    = gen_pesel(rng)
    id_card  = gen_id_card(rng)
    phone    = gen_phone(rng)
    email    = gen_email(rng, person)
    birth    = gen_birth_date(rng)
    nip      = gen_nip(rng)

    entities: dict[str, Any] = {
        "imie_nazwisko": person["full"],
        "adres": address["full"],
        "pesel": pesel,
        "dowod_osobisty": id_card,
        "telefon": phone,
        "email": email,
        "data_urodzenia": birth,
        "nip": nip,
    }

    # Optional: passport
    passport_block: list[Block] = []
    if rng.random() < 0.35:
        pp = gen_passport(rng)
        entities["numer_paszportu"] = pp
        passport_block = list(_rows(("Nr paszportu", pp)))

    # Optional: real estate fields
    re_blocks: list[Block] = []
    if rng.random() < 0.30:
        kw = gen_kw_number(rng)
        entities["numer_kw"] = kw
        re_blocks = [
            Block("DANE NIERUCHOMOŚCI", "heading", bold=True),
            *_sp(),
            *_rows(("Nr księgi wieczystej", kw)),
        ]
        if rng.random() < 0.5:
            dz = gen_dzialka_number(rng)
            entities["numer_dzialki"] = dz
            re_blocks += list(_rows(("Nr działki ewidencyjnej", dz)))
        re_blocks += _sp()

    form_title = rng.choice([
        "FORMULARZ REJESTRACYJNY",
        "WNIOSEK O WYDANIE ZAŚWIADCZENIA",
        "FORMULARZ DANYCH OSOBOWYCH",
        "ZGŁOSZENIE DO REJESTRU",
        "WNIOSEK O ZMIANĘ DANYCH",
    ])

    blocks = [
        Block(form_title, "title", bold=True, align="center"),
        *_sp(),
        _sep(),
        *_sp(),
        Block("DANE OSOBOWE WNIOSKODAWCY", "heading", bold=True),
        *_sp(),
        *_rows(
            ("Imię i nazwisko", person["full"]),
            ("Data urodzenia",  birth),
            ("PESEL",           pesel),
            ("Nr dowodu osobistego", id_card),
        ),
        *passport_block,
        *_sp(),
        Block("DANE KONTAKTOWE", "heading", bold=True),
        *_sp(),
        *_rows(
            ("Adres zamieszkania", address["full"]),
            ("Telefon",           phone),
            ("Adres e-mail",      email),
        ),
        *_sp(),
        Block("DANE PODATKOWE", "heading", bold=True),
        *_sp(),
        *_rows(("NIP (jeśli dotyczy)", nip)),
        *_sp(),
        *re_blocks,
        Block(
            "Oświadczam, że podane dane są zgodne ze stanem faktycznym. "
            "Wyrażam zgodę na przetwarzanie moich danych osobowych zgodnie z art. 6 ust. 1 lit. b) RODO.",
            "small"),
        *_sp(3),
        Block("Data: ........................", "body"),
        Block("Podpis: ........................", "body"),
        *_sp(),
        _sep(),
    ]
    return blocks, entities


# ── 3. Umowa ─────────────────────────────────────────────────────────────────
def build_umowa(rng: random.Random) -> DocResult:
    person_a  = gen_person(rng)
    person_b  = gen_person(rng)
    addr_a    = gen_address(rng)
    addr_b    = gen_address(rng)
    contract  = gen_contract_number(rng)
    doc_date  = gen_doc_date(rng)
    iban      = gen_iban(rng)
    nip_a     = gen_nip(rng)
    nip_b     = gen_nip(rng)
    pesel_a   = gen_pesel(rng)
    amount    = rng.randint(500, 80000)

    entities: dict[str, Any] = {
        "imie_nazwisko_zleceniodawca": person_a["full"],
        "adres_zleceniodawca": addr_a["full"],
        "nip_zleceniodawca": nip_a,
        "pesel_zleceniodawca": pesel_a,
        "imie_nazwisko_zleceniobiorca": person_b["full"],
        "adres_zleceniobiorca": addr_b["full"],
        "nip_zleceniobiorca": nip_b,
        "numer_umowy": contract,
        "iban": iban,
    }

    blocks = [
        Block("UMOWA O ŚWIADCZENIE USŁUG", "title", bold=True, align="center"),
        Block(f"nr {contract}", "heading", align="center"),
        *_sp(),
        Block(f"zawarta dnia {doc_date} pomiędzy:", "body"),
        *_sp(),
        Block(f"1. {person_a['full']}", "body", bold=True, indent=20),
        Block(f"   zamieszkałym/ą: {addr_a['full']}", "body", indent=20),
        Block(f"   NIP: {nip_a}  |  PESEL: {pesel_a}", "body", indent=20),
        Block('   zwany/ą dalej „Zleceniodawcą"', "body", indent=20),
        *_sp(),
        Block("a", "body"),
        *_sp(),
        Block(f"2. {person_b['full']}", "body", bold=True, indent=20),
        Block(f"   zamieszkałym/ą: {addr_b['full']}", "body", indent=20),
        Block(f"   NIP: {nip_b}", "body", indent=20),
        Block('   zwany/ą dalej „Zleceniobiorcą"', "body", indent=20),
        *_sp(),
        _sep(),
        Block("§ 1. Przedmiot umowy", "heading", bold=True),
        Block(
            "Zleceniodawca zleca, a Zleceniobiorca przyjmuje do wykonania usługi w zakresie "
            "doradztwa i wsparcia technicznego zgodnie ze specyfikacją stanowiącą Załącznik nr 1.",
            "body"),
        *_sp(),
        Block("§ 2. Wynagrodzenie i płatność", "heading", bold=True),
        Block(
            f"Za wykonanie przedmiotu umowy Zleceniodawca zapłaci wynagrodzenie w kwocie "
            f"{amount:,} PLN brutto (słownie: {_num_words(amount)} złotych).",
            "body"),
        Block("Zapłata nastąpi przelewem na rachunek bankowy Zleceniobiorcy:", "body"),
        Block(f"IBAN: {iban}", "body", bold=True, indent=20),
        *_sp(),
        Block("§ 3. Czas trwania umowy", "heading", bold=True),
        Block(
            "Umowa zostaje zawarta na czas określony 12 miesięcy od daty podpisania. "
            "Strony mogą przedłużyć umowę na kolejny okres za obustronnym porozumieniem.",
            "body"),
        *_sp(),
        Block("§ 4. Poufność", "heading", bold=True),
        Block(
            "Strony zobowiązują się do zachowania poufności wszelkich informacji uzyskanych "
            "w trakcie wykonywania niniejszej umowy przez okres 5 lat od jej wygaśnięcia.",
            "body"),
        *_sp(2),
        Block("Zleceniodawca:", "body"),
        *_sp(3),
        Block("................................................", "body"),
        Block(person_a["full"], "small"),
        *_sp(),
        Block("Zleceniobiorca:", "body"),
        *_sp(3),
        Block("................................................", "body"),
        Block(person_b["full"], "small"),
    ]
    return blocks, entities


# ── 4. Faktura ───────────────────────────────────────────────────────────────
def build_faktura(rng: random.Random) -> DocResult:
    seller      = rng.choice(_COMPANIES)
    buyer       = gen_person(rng)
    addr_seller = gen_address(rng)
    addr_buyer  = gen_address(rng)
    nip_seller  = gen_nip(rng)
    nip_buyer   = gen_nip(rng)
    regon       = gen_regon(rng)
    iban        = gen_iban(rng)
    inv_num     = gen_invoice_number(rng)
    client_num  = gen_client_number(rng)
    doc_date    = gen_doc_date(rng)

    entities: dict[str, Any] = {
        "numer_faktury": inv_num,
        "nip_sprzedawcy": nip_seller,
        "regon_sprzedawcy": regon,
        "nip_nabywcy": nip_buyer,
        "iban": iban,
        "numer_klienta": client_num,
        "imie_nazwisko_nabywcy": buyer["full"],
        "adres_nabywcy": addr_buyer["full"],
    }

    _services = [
        "Usługa doradcza", "Konsultacja techniczna", "Usługa serwisowa",
        "Naprawa sprzętu", "Instalacja oprogramowania", "Szkolenie zawodowe",
        "Usługa transportowa", "Wynajem sprzętu biurowego",
        "Obsługa prawna", "Usługa projektowa",
    ]
    items = []
    for _ in range(rng.randint(2, 5)):
        name    = rng.choice(_services)
        qty     = rng.randint(1, 10)
        price   = rng.randint(50, 2000)
        vat     = rng.choice([8, 23])
        net     = qty * price
        vat_amt = round(net * vat / 100)
        gross   = net + vat_amt
        items.append((name, qty, price, vat, net, vat_amt, gross))
    total_gross = sum(i[6] for i in items)

    # Table header (monospace for alignment)
    th = f"{'Lp.':<4} {'Nazwa usługi':<30} {'Il.':>4} {'Cena':>7} {'VAT':>4} {'Netto':>8} {'Brutto':>8}"
    table_rows = [Block(th, "mono", bold=True, mono=True)]
    table_rows.append(_sep())
    for idx, (name, qty, price, vat, net, _, gross) in enumerate(items, 1):
        row_txt = (f"{str(idx)+'.':<4} {name[:30]:<30} {qty:>4} {price:>7} "
                   f"{vat:>3}% {net:>8} {gross:>8}")
        table_rows.append(Block(row_txt, "mono", mono=True))

    blocks = [
        Block("FAKTURA VAT", "title", bold=True, align="center"),
        Block(f"Nr {inv_num}", "heading", bold=True, align="center"),
        *_sp(),
        Block(f"Data wystawienia: {doc_date}", "body"),
        Block(f"Numer klienta: {client_num}", "body"),
        *_sp(),
        _sep(),
        Block("SPRZEDAWCA:", "label", bold=True),
        Block(seller, "body", bold=True),
        Block(f"Adres: {addr_seller['full']}", "body"),
        Block(f"NIP: {nip_seller}   REGON: {regon}", "body"),
        Block(f"Nr konta: {iban}", "body"),
        *_sp(),
        Block("NABYWCA:", "label", bold=True),
        Block(buyer["full"], "body", bold=True),
        Block(f"Adres: {addr_buyer['full']}", "body"),
        Block(f"NIP: {nip_buyer}", "body"),
        *_sp(),
        _sep(),
        *table_rows,
        _sep(),
        Block(f"{'ŁĄCZNIE DO ZAPŁATY:':<54}  {total_gross:>8} PLN", "mono", bold=True, mono=True),
        *_sp(),
        Block(f"Płatność przelewem: {iban}", "body", bold=True),
        Block("Termin płatności: 14 dni od daty wystawienia faktury.", "body"),
        *_sp(2),
        Block("Wystawił/a:                        Odebrał/a:", "body"),
        *_sp(3),
        Block("...........................        ...........................", "body"),
    ]
    return blocks, entities


# ── 5. Notatka ───────────────────────────────────────────────────────────────
def build_notatka(rng: random.Random) -> DocResult:
    author   = gen_person(rng)
    person   = gen_person(rng)
    doc_date = gen_doc_date(rng)
    pesel    = gen_pesel(rng)
    phone    = gen_phone(rng)
    address  = gen_address(rng)

    entities: dict[str, Any] = {
        "autor": author["full"],
        "osoba": person["full"],
        "pesel": pesel,
        "telefon": phone,
        "adres": address["full"],
    }

    topics = [
        "zgłoszenia awarii instalacji",
        "prośby o udostępnienie informacji publicznej",
        "skargi na warunki lokalowe",
        "wniosku o zmianę adresu zameldowania",
        "zgłoszenia zaginięcia dokumentu tożsamości",
        "interwencji dotyczącej zadłużenia czynszowego",
    ]

    blocks = [
        Block("NOTATKA SŁUŻBOWA", "title", bold=True, align="center"),
        *_sp(),
        Block(f"Data: {doc_date}", "body"),
        Block(f"Sporządził/a: {author['full']}", "body"),
        *_sp(),
        _sep(),
        *_sp(),
        Block(f"Dotyczy: {rng.choice(topics)}", "heading", bold=True),
        *_sp(),
        Block(f"W dniu {doc_date} do biura zgłosiła/się następująca osoba:", "body"),
        *_sp(),
        Block(f"{person['full']}", "body", bold=True, indent=20),
        Block(f"PESEL: {pesel}", "body", indent=20),
        Block(f"Adres: {address['full']}", "body", indent=20),
        Block(f"Tel.: {phone}", "body", indent=20),
        *_sp(),
        Block(
            "Rozmowa trwała ok. 20 minut. Sprawa została zarejestrowana w systemie "
            "ewidencji korespondencji. Zainteresowanemu/ej wskazano wymagane dokumenty "
            "oraz termin złożenia wniosku.",
            "body"),
        *_sp(),
        Block(
            "Zalecono złożenie pisemnego wniosku wraz z dokumentami potwierdzającymi "
            "tożsamość w terminie 7 dni roboczych.",
            "body"),
        *_sp(3),
        Block(author["full"], "body"),
        Block("(podpis sporządzającego)", "small"),
        *_sp(),
        _sep(),
    ]
    return blocks, entities


# ── 6. Wezwanie ──────────────────────────────────────────────────────────────
def build_wezwanie(rng: random.Random) -> DocResult:
    person   = gen_person(rng)
    address  = gen_address(rng)
    pesel    = gen_pesel(rng)
    sygn     = gen_sygn_komornicza(rng)
    doc_date = gen_doc_date(rng)
    iban     = gen_iban(rng)
    bailiff  = rng.choice(_BAILIFFS)
    amount   = rng.randint(200, 80000)

    entities: dict[str, Any] = {
        "imie_nazwisko": person["full"],
        "adres": address["full"],
        "pesel": pesel,
        "sygnatura_komornicza": sygn,
        "iban": iban,
    }

    blocks = [
        Block(bailiff, "heading", bold=True),
        *_sp(),
        Block(f"Sygn. akt: {sygn}", "body", bold=True),
        Block(f"Data: {doc_date}", "body"),
        *_sp(2),
        Block(person["full"], "body", bold=True),
        Block(f"{address['street']} {address['house']}", "body"),
        Block(f"{address['postcode']} {address['city']}", "body"),
        *_sp(2),
        Block("ZAWIADOMIENIE O WSZCZĘCIU EGZEKUCJI", "title", bold=True, align="center"),
        *_sp(),
        Block(
            f"Na podstawie tytułu wykonawczego komornik sądowy zawiadamia, że w sprawie "
            f"prowadzonej pod sygn. {sygn} wszczął postępowanie egzekucyjne przeciwko dłużnikowi:",
            "body"),
        *_sp(),
        Block(f"{person['full']}, PESEL: {pesel}", "body", bold=True, indent=40),
        Block(f"zamieszkały/a: {address['full']}", "body", indent=40),
        *_sp(),
        Block(
            f"Łączna kwota zadłużenia wynosi: {amount:,} PLN "
            f"(słownie: {_num_words(amount)} złotych).",
            "body", bold=True),
        *_sp(),
        Block(
            "Dłużnik ma prawo złożyć oświadczenie o składnikach majątku w terminie "
            "7 dni od dnia doręczenia niniejszego zawiadomienia.",
            "body"),
        Block(f"Spłata możliwa na konto komornika: {iban}", "body"),
        *_sp(2),
        Block("Komornik Sądowy:", "body"),
        *_sp(3),
        Block("................................................", "body"),
        Block(bailiff, "small"),
        *_sp(),
        _sep(),
        Block(
            "POUCZENIE: Niewykonanie obowiązku złożenia oświadczenia o majątku skutkuje "
            "odpowiedzialnością karną z art. 276 k.k.",
            "small"),
    ]
    return blocks, entities


# ── 7. Decyzja administracyjna ───────────────────────────────────────────────
def build_decyzja(rng: random.Random) -> DocResult:
    person   = gen_person(rng)
    address  = gen_address(rng)
    pesel    = gen_pesel(rng)
    sygn     = gen_sygn_admin(rng)
    doc_date = gen_doc_date(rng)
    body_org = rng.choice(_ADMIN_BODIES)
    nip      = gen_nip(rng)
    city     = rng.choice(_CITIES)

    entities: dict[str, Any] = {
        "imie_nazwisko": person["full"],
        "adres": address["full"],
        "pesel": pesel,
        "sygnatura_administracyjna": sygn,
        "nip": nip,
    }

    # Optional: real estate context
    if rng.random() < 0.25:
        kw = gen_kw_number(rng)
        entities["numer_kw"] = kw

    decision_cases = [
        ("przyznaniu zasiłku stałego",           "orzekam przyznać świadczenie na warunkach określonych w uzasadnieniu"),
        ("odmowie wydania zezwolenia",            "odmawiam wydania wnioskowanego zezwolenia z przyczyn podanych w uzasadnieniu"),
        ("wpisie do rejestru działalności",       "orzekam o wpisaniu podmiotu do rejestru pod numerem określonym w uzasadnieniu"),
        ("nałożeniu kary pieniężnej",             f"nakładam karę pieniężną w wysokości {rng.randint(500,5000):,} PLN"),
        ("zmianie decyzji w sprawie świadczenia", "orzekam o zmianie decyzji pierwotnej z przyczyn wskazanych poniżej"),
        ("umorzeniu postępowania",                "umarza się postępowanie administracyjne w całości"),
    ]
    topic, disposition = rng.choice(decision_cases)

    blocks = [
        Block(body_org, "heading", bold=True, align="center"),
        *_sp(),
        Block(f"Sygn.: {sygn}", "body", bold=True),
        Block(f"Data wydania: {doc_date}", "body"),
        *_sp(2),
        Block("DECYZJA ADMINISTRACYJNA", "title", bold=True, align="center"),
        Block(f"w sprawie {topic}", "heading", align="center"),
        *_sp(),
        _sep(),
        *_sp(),
        Block("Na podstawie:", "body", bold=True),
        Block(
            "art. 104 ustawy z dnia 14 czerwca 1960 r. – Kodeks postępowania "
            "administracyjnego (t.j. Dz. U. z 2023 r. poz. 775 ze zm.)",
            "body", indent=20),
        *_sp(),
        Block("po rozpatrzeniu wniosku:", "body"),
        Block(person["full"], "body", bold=True, indent=20),
        Block(f"PESEL: {pesel}", "body", indent=20),
        Block(f"NIP: {nip}", "body", indent=20),
        Block(f"adres: {address['full']}", "body", indent=20),
        *_sp(),
        Block("ORZEKAM:", "heading", bold=True),
        *_sp(),
        Block(f"Organ {disposition}.", "body"),
        *_sp(),
        Block("UZASADNIENIE:", "heading", bold=True),
        Block(
            "Po przeprowadzeniu postępowania administracyjnego i zebraniu materiału dowodowego "
            "organ stwierdził, że zachodzą przesłanki uzasadniające wydanie powyższego "
            "rozstrzygnięcia. Strona spełniła/nie spełniła wymogi formalne i materialnoprawne.",
            "body"),
        *_sp(),
        Block("POUCZENIE:", "heading", bold=True),
        Block(
            "Od niniejszej decyzji służy stronie odwołanie do Samorządowego Kolegium "
            "Odwoławczego, złożone za pośrednictwem organu, który wydał decyzję, w terminie "
            "14 dni od dnia jej doręczenia.",
            "body"),
        *_sp(2),
        Block(f"{city}, dnia {doc_date}", "body"),
        *_sp(3),
        Block("................................................", "body"),
        Block("Podpis i pieczęć organu", "small"),
        *_sp(),
        _sep(),
        Block(f"Sygn.: {sygn}   |   {body_org}", "small", align="center"),
    ]
    return blocks, entities


_BUILDERS: dict[str, Any] = {
    "pismo_urzedowe":        build_pismo_urzedowe,
    "formularz":             build_formularz,
    "umowa":                 build_umowa,
    "faktura":               build_faktura,
    "notatka":               build_notatka,
    "wezwanie":              build_wezwanie,
    "decyzja_administracyjna": build_decyzja,
}


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 7 — Image renderer
# ─────────────────────────────────────────────────────────────────────────────

def _text_width(draw: ImageDraw.Draw, text: str, font: ImageFont.ImageFont) -> int:
    try:
        bbox = draw.textbbox((0, 0), text, font=font)
        return bbox[2] - bbox[0]
    except Exception:
        return len(text) * (getattr(font, "size", 13) // 2 + 1)


def _wrap(draw: ImageDraw.Draw, text: str, font: ImageFont.ImageFont, max_w: int) -> list[str]:
    words = text.split()
    if not words:
        return [""]
    lines: list[str] = []
    current = words[0]
    for word in words[1:]:
        candidate = current + " " + word
        if _text_width(draw, candidate, font) <= max_w:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return lines


def render_document(blocks: list[Block], rng: random.Random) -> Image.Image:
    """Render text blocks onto an A4-sized RGB image."""
    bg_color = (rng.randint(248, 255), rng.randint(248, 255), rng.randint(246, 254))
    img  = Image.new("RGB", (A4_W, A4_H), color=bg_color)
    draw = ImageDraw.Draw(img)
    text_w = A4_W - 2 * MARGIN
    y = MARGIN

    for blk in blocks:
        cfg = STYLE.get(blk.style, STYLE["body"])

        if blk.style == "separator":
            sy = y + 5
            draw.line([(MARGIN, sy), (A4_W - MARGIN, sy)], fill=(180, 180, 180), width=1)
            y += cfg["lh"]
            continue

        if blk.style == "spacer" or not blk.text:
            y += cfg["lh"]
            continue

        bold  = blk.bold or cfg["bold"]
        mono  = blk.mono
        font  = get_font(cfg["size"], bold=bold, mono=mono)
        color = cfg["color"]
        text  = _tr(blk.text)
        avail = text_w - blk.indent

        for line in _wrap(draw, text, font, avail):
            if y + cfg["lh"] > A4_H - MARGIN // 2:
                break
            x = MARGIN + blk.indent
            if blk.align == "center":
                tw = _text_width(draw, line, font)
                x  = max(MARGIN, (A4_W - tw) // 2)
            elif blk.align == "right":
                tw = _text_width(draw, line, font)
                x  = A4_W - MARGIN - tw
            draw.text((x, y), line, font=font, fill=color)
            y += cfg["lh"] + LINE_GAP

    return img


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 8 — Degradation pipeline
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class DegParams:
    level:                int
    blur_radius:          float = 0.0
    noise_sigma:          float = 0.0
    contrast_factor:      float = 1.0
    rotation_deg:         float = 0.0
    perspective_strength: float = 0.0
    shadow_coverage:      float = 0.0
    gradient_strength:    float = 0.0


def _np_rng(rng: random.Random) -> np.random.Generator:
    return np.random.default_rng(rng.randint(0, 2**32 - 1))


def _noise(img: Image.Image, sigma: float, rng: random.Random) -> Image.Image:
    if sigma <= 0:
        return img
    arr = np.asarray(img, dtype=np.float32)
    arr = np.clip(arr + _np_rng(rng).normal(0.0, sigma, arr.shape), 0, 255)
    return Image.fromarray(arr.astype(np.uint8))


def _gradient(img: Image.Image, strength: float, rng: random.Random) -> Image.Image:
    """Uneven illumination gradient simulating off-angle lighting."""
    arr = np.asarray(img, dtype=np.float32)
    H, W = arr.shape[:2]
    direction = rng.choice(["lr", "tb", "diag"])

    if direction == "lr":
        side = rng.choice([-1, 1])
        gx   = np.linspace(1.0 - strength * 0.35 * (side < 0),
                            1.0 - strength * 0.35 * (side > 0), W, dtype=np.float32)
        g = np.tile(gx, (H, 1))
    elif direction == "tb":
        gy = np.linspace(1.0, 1.0 - strength * 0.25, H, dtype=np.float32)
        g  = np.tile(gy.reshape(-1, 1), (1, W))
    else:
        gx = np.linspace(1.0 - strength * 0.2, 1.0, W, dtype=np.float32)
        gy = np.linspace(1.0 - strength * 0.2, 1.0, H, dtype=np.float32)
        g  = np.outer(gy, gx).astype(np.float32)

    if arr.ndim == 3:
        g = g[:, :, np.newaxis]
    return Image.fromarray(np.clip(arr * g, 0, 255).astype(np.uint8))


def _shadow(img: Image.Image, coverage: float, rng: random.Random) -> Image.Image:
    """Hard shadow cast from one edge or corner."""
    arr  = np.asarray(img, dtype=np.float32)
    H, W = arr.shape[:2]
    mask = np.ones((H, W), dtype=np.float32)
    dark = rng.uniform(0.35, 0.65)
    side = rng.choice(["left", "right", "top", "bottom", "corner"])

    if side == "left":
        sw = int(W * coverage)
        mask[:, :sw] = np.linspace(dark, 1.0, sw, dtype=np.float32)
    elif side == "right":
        sw = int(W * coverage)
        mask[:, W - sw:] = np.linspace(1.0, dark, sw, dtype=np.float32)
    elif side == "top":
        sh = int(H * coverage)
        mask[:sh, :] = np.linspace(dark, 1.0, sh, dtype=np.float32).reshape(-1, 1)
    elif side == "bottom":
        sh = int(H * coverage)
        mask[H - sh:, :] = np.linspace(1.0, dark, sh, dtype=np.float32).reshape(-1, 1)
    else:
        cx = rng.choice([0, W])
        cy = rng.choice([0, H])
        Y, X = np.ogrid[:H, :W]
        dist = np.hypot(X - cx, Y - cy)
        max_d = math.hypot(W, H) * coverage * 1.3
        mask  = np.clip(dark + (1.0 - dark) * (dist / max_d), dark, 1.0).astype(np.float32)

    if arr.ndim == 3:
        mask = mask[:, :, np.newaxis]
    return Image.fromarray(np.clip(arr * mask, 0, 255).astype(np.uint8))


def _persp_coeffs(pa: list[tuple[int, int]], pb: list[tuple[int, int]]) -> list[float]:
    """
    Compute 8 PIL perspective coefficients mapping output corners (pa) → input corners (pb).

    PIL formula:  X = (a*x + b*y + c) / (g*x + h*y + 1)
                  Y = (d*x + e*y + f) / (g*x + h*y + 1)
    where (x,y) = output pixel, (X,Y) = input pixel.
    """
    A = np.zeros((8, 8), dtype=np.float64)
    b = np.zeros(8, dtype=np.float64)
    for i, ((ox, oy), (ix, iy)) in enumerate(zip(pa, pb)):
        A[2*i]   = [ox, oy, 1, 0,  0,  0, -ix*ox, -ix*oy]
        A[2*i+1] = [0,  0,  0, ox, oy, 1, -iy*ox, -iy*oy]
        b[2*i]   = ix
        b[2*i+1] = iy
    try:
        coeffs = np.linalg.solve(A, b)
    except np.linalg.LinAlgError:
        coeffs, *_ = np.linalg.lstsq(A, b, rcond=None)
    return coeffs.tolist()


def _perspective(img: Image.Image, strength: float, rng: random.Random) -> Image.Image:
    """Simulate document photographed at an angle (trapezoid distortion)."""
    W, H = img.size
    mdx = max(1, int(W * strength * 0.12))
    mdy = max(1, int(H * strength * 0.09))
    tilt = rng.choice(["top_narrow", "bottom_narrow", "left_narrow", "right_narrow", "diagonal"])

    src = [(0, 0), (W, 0), (W, H), (0, H)]   # output corners

    if tilt == "top_narrow":
        off = rng.randint(mdx // 2, mdx)
        dst = [(off, 0), (W - off, 0), (W, H), (0, H)]
    elif tilt == "bottom_narrow":
        off = rng.randint(mdx // 2, mdx)
        dst = [(0, 0), (W, 0), (W - off, H), (off, H)]
    elif tilt == "left_narrow":
        off = rng.randint(mdy // 2, mdy)
        dst = [(0, off), (W, 0), (W, H), (0, H - off)]
    elif tilt == "right_narrow":
        off = rng.randint(mdy // 2, mdy)
        dst = [(0, 0), (W, off), (W, H - off), (0, H)]
    else:
        ox = rng.randint(mdx // 4, mdx // 2)
        oy = rng.randint(mdy // 4, mdy // 2)
        dst = [(ox, oy), (W - ox // 2, oy // 2), (W, H), (0, H - oy)]

    try:
        coeffs = _persp_coeffs(src, dst)
        bg = (235, 230, 220)
        return img.transform((W, H), _PERSPECTIVE, coeffs, _BICUBIC, fillcolor=bg)
    except Exception:
        return img   # degenerate case — return clean image


def apply_degradation(
    img: Image.Image,
    level: int,
    rng: random.Random,
) -> tuple[Image.Image, DegParams]:
    """Apply level-based degradation. Returns (degraded_image, params_used)."""
    p = DegParams(level=level)

    if level == 0:
        # Perfect scan — trace paper texture only
        p.noise_sigma = rng.uniform(0.0, 2.5)
        p.contrast_factor = 1.0
        img = _noise(img, p.noise_sigma, rng)

    elif level == 1:
        # Light noise + slight rotation
        p.noise_sigma    = rng.uniform(2.0, 6.0)
        p.rotation_deg   = rng.uniform(-1.0, 1.0)
        p.contrast_factor = rng.uniform(0.93, 1.0)
        img = _noise(img, p.noise_sigma, rng)
        img = img.rotate(p.rotation_deg, fillcolor=(242, 242, 240), expand=False)
        if p.contrast_factor < 1.0:
            img = ImageEnhance.Contrast(img).enhance(p.contrast_factor)

    elif level == 2:
        # Noise + blur + lowered contrast
        p.noise_sigma     = rng.uniform(5.0, 12.0)
        p.blur_radius     = rng.uniform(0.5, 1.8)
        p.contrast_factor = rng.uniform(0.70, 0.92)
        p.rotation_deg    = rng.uniform(-0.6, 0.6)
        img = _noise(img, p.noise_sigma, rng)
        img = img.filter(ImageFilter.GaussianBlur(radius=p.blur_radius))
        img = ImageEnhance.Contrast(img).enhance(p.contrast_factor)
        img = img.rotate(p.rotation_deg, fillcolor=(238, 237, 234), expand=False)

    elif level == 3:
        # Good phone photo — perspective + light gradient
        p.noise_sigma          = rng.uniform(8.0, 16.0)
        p.blur_radius          = rng.uniform(0.3, 1.2)
        p.contrast_factor      = rng.uniform(0.80, 0.96)
        p.perspective_strength = rng.uniform(0.05, 0.14)
        p.gradient_strength    = rng.uniform(0.2, 0.5)
        p.rotation_deg         = rng.uniform(-2.5, 2.5)
        img = _perspective(img, p.perspective_strength, rng)
        img = _gradient(img, p.gradient_strength, rng)
        img = _noise(img, p.noise_sigma, rng)
        if p.blur_radius > 0.1:
            img = img.filter(ImageFilter.GaussianBlur(radius=p.blur_radius))
        img = ImageEnhance.Contrast(img).enhance(p.contrast_factor)
        img = img.rotate(p.rotation_deg, fillcolor=(225, 220, 208), expand=False)

    elif level == 4:
        # Typical user photo — blur + shadow + uneven light
        p.noise_sigma          = rng.uniform(12.0, 22.0)
        p.blur_radius          = rng.uniform(1.0, 3.2)
        p.contrast_factor      = rng.uniform(0.58, 0.80)
        p.perspective_strength = rng.uniform(0.10, 0.22)
        p.shadow_coverage      = rng.uniform(0.10, 0.28)
        p.gradient_strength    = rng.uniform(0.30, 0.65)
        p.rotation_deg         = rng.uniform(-3.5, 3.5)
        img = _perspective(img, p.perspective_strength, rng)
        img = _gradient(img, p.gradient_strength, rng)
        img = _shadow(img, p.shadow_coverage, rng)
        img = _noise(img, p.noise_sigma, rng)
        img = img.filter(ImageFilter.GaussianBlur(radius=p.blur_radius))
        img = ImageEnhance.Contrast(img).enhance(p.contrast_factor)
        img = img.rotate(p.rotation_deg, fillcolor=(205, 200, 190), expand=False)

    elif level == 5:
        # Poor quality — heavy everything
        p.noise_sigma          = rng.uniform(22.0, 44.0)
        p.blur_radius          = rng.uniform(3.0, 6.5)
        p.contrast_factor      = rng.uniform(0.42, 0.65)
        p.perspective_strength = rng.uniform(0.20, 0.38)
        p.shadow_coverage      = rng.uniform(0.25, 0.48)
        p.gradient_strength    = rng.uniform(0.50, 0.85)
        p.rotation_deg         = rng.uniform(-6.0, 6.0)
        img = _perspective(img, p.perspective_strength, rng)
        img = _gradient(img, p.gradient_strength, rng)
        img = _shadow(img, p.shadow_coverage, rng)
        img = _noise(img, p.noise_sigma, rng)
        img = img.filter(ImageFilter.GaussianBlur(radius=p.blur_radius))
        img = ImageEnhance.Contrast(img).enhance(p.contrast_factor)
        img = img.rotate(p.rotation_deg, fillcolor=(190, 185, 170), expand=False)

    return img, p


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 9 — Quality score
# ─────────────────────────────────────────────────────────────────────────────

_MAX_BLUR   = 6.5
_MAX_NOISE  = 44.0
_MAX_ROT    = 6.0
_MAX_PERSP  = 0.38
_MAX_SHADOW = 0.48
_MIN_CONTR  = 0.42


def quality_score(p: DegParams, rng: random.Random) -> int:
    """
    Return quality score 0–100 as weighted combination of degradation factors.
    Small jitter (±3) is added for realism.
    """
    blur_s    = max(0.0, 1.0 - p.blur_radius          / _MAX_BLUR)
    noise_s   = max(0.0, 1.0 - p.noise_sigma          / _MAX_NOISE)
    contr_s   = max(0.0, (p.contrast_factor - _MIN_CONTR) / (1.0 - _MIN_CONTR))
    rot_s     = max(0.0, 1.0 - abs(p.rotation_deg)    / _MAX_ROT)
    persp_s   = max(0.0, 1.0 - p.perspective_strength / _MAX_PERSP)
    shadow_s  = max(0.0, 1.0 - p.shadow_coverage      / _MAX_SHADOW)
    grad_s    = max(0.0, 1.0 - p.gradient_strength)

    composite = (
        blur_s   * 0.28 +
        noise_s  * 0.20 +
        contr_s  * 0.18 +
        rot_s    * 0.10 +
        persp_s  * 0.12 +
        shadow_s * 0.07 +
        grad_s   * 0.05
    )
    return max(0, min(100, round(composite * 100) + rng.randint(-3, 3)))


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 10 — Dataset generator
# ─────────────────────────────────────────────────────────────────────────────

def _measure_real_conf(img: Image.Image) -> float | None:
    """
    Uruchamia Tesseract na obrazie i zwraca rzeczywisty avg confidence (0-100).
    Używa tej samej logiki penalizacji co ocr_engine.py (_quality_from_confidence).
    Zwraca None gdy pytesseract niedostępny — caller zapisuje null w ground_truth.

    Dodane w v1.1 (Potok OCR-2) — kalibracja quality_score vs rzeczywisty conf.
    """
    try:
        import pytesseract

        # Kandydaci ścieżki Tesseract (Windows) — ta sama lista co ocr_engine.py
        _candidates = [
            r"C:\Program Files\Tesseract-OCR\tesseract.exe",
            r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\Tesseract-OCR\tesseract.exe"),
        ]
        try:
            pytesseract.get_tesseract_version()
        except Exception:
            for c in _candidates:
                if os.path.isfile(c):
                    pytesseract.tesseract_cmd = c
                    try:
                        pytesseract.get_tesseract_version()
                        break
                    except Exception:
                        continue
            else:
                return None

        try:
            langs = pytesseract.get_languages()
            lang = "pol" if "pol" in langs else "eng"
        except Exception:
            lang = "eng"

        # Konwertuj do RGB (Tesseract nie lubi RGBA)
        if img.mode in ("RGBA", "P", "LA"):
            img = img.convert("RGB")

        data = pytesseract.image_to_data(
            img, lang=lang, config="--psm 3",
            output_type=pytesseract.Output.DICT,
        )
        confs = [c for c in data["conf"] if c >= 0]
        if not confs:
            return 0.0

        avg = sum(confs) / len(confs)
        # Penalizacja jak w ocr_engine.py — dużo słów conf<30 = gorsza jakość
        low_ratio = sum(1 for c in confs if c < 30) / len(confs)
        if low_ratio > 0.3:
            avg = avg * (1 - low_ratio * 0.5)
        return round(avg, 1)
    except Exception:
        return None


def generate_dataset(
    count:    int,
    out_dir:  str,
    seed:     int | None = None,
    balanced: bool = True,
    measure_conf: bool = False,
    max_level: int = 5,
) -> None:
    out_path    = Path(out_dir)
    images_path = out_path / "images"
    images_path.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed)
    if seed is not None:
        np.random.seed(seed)

    # Assign degradation levels
    levels = list(range(max_level + 1))
    if balanced:
        per_level = count // len(levels)
        pool = []
        for lvl in levels:
            pool.extend([lvl] * per_level)
        for i in range(count - len(pool)):
            pool.append(levels[i % len(levels)])
        rng.shuffle(pool)
    else:
        pool = [rng.choice(levels) for _ in range(count)]

    doc_types = list(_BUILDERS.keys())
    ground_truth: list[dict[str, Any]] = []

    # ── header ──
    print(f"\nOCR Dataset Generator — Synthetic Polish Documents")
    print(f"{'─'*52}")
    print(f"  Documents : {count}")
    print(f"  Output    : {out_path.resolve()}")
    print(f"  Seed      : {seed if seed is not None else 'random'}")
    print(f"  Balance   : {'yes' if balanced else 'no'}")
    print(f"  Max level : {max_level} (poziomy 0–{max_level})")
    print(f"  Font      : {_font_regular or 'PIL default (ASCII fallback)'}")
    print(f"  Real conf : {'yes (Tesseract)' if measure_conf else 'no (dodaj --measure-conf by włączyć)'}")
    print()

    bar_width = 46

    for i in range(count):
        # ── progress bar ──
        if i % max(1, count // bar_width) == 0 or i == count - 1:
            pct    = (i + 1) / count
            filled = int(bar_width * pct)
            bar    = "█" * filled + "░" * (bar_width - filled)
            print(f"\r  [{bar}]  {i+1}/{count}", end="", flush=True)

        doc_type = rng.choice(doc_types)
        level    = pool[i]

        # Build, render, degrade
        blocks, entities = _BUILDERS[doc_type](rng)
        img = render_document(blocks, rng)
        img, deg = apply_degradation(img, level, rng)
        qs  = quality_score(deg, rng)

        # Save image
        fname = f"doc_{i:05d}.png"
        img.save(str(images_path / fname), format="PNG")

        # Real OCR confidence — opcjonalne, wymaga Tesseract
        # Mierzone na już zdegradowanym obrazie (to co faktycznie widzi pipeline)
        real_conf: float | None = None
        if measure_conf:
            real_conf = _measure_real_conf(img)

        ground_truth.append({
            "file":             f"images/{fname}",
            "doc_type":         doc_type,
            "quality_score":    qs,
            "ocr_conf_real":    real_conf,
            "degradation_level": level,
            "degradation_params": {
                "blur_radius":          round(deg.blur_radius,          3),
                "noise_sigma":          round(deg.noise_sigma,          3),
                "contrast_factor":      round(deg.contrast_factor,      3),
                "rotation_deg":         round(deg.rotation_deg,         3),
                "perspective_strength": round(deg.perspective_strength, 3),
                "shadow_coverage":      round(deg.shadow_coverage,      3),
                "gradient_strength":    round(deg.gradient_strength,    3),
            },
            "entities": entities,
        })

    print()   # close progress line

    # Save ground truth
    gt_path = out_path / "ground_truth.json"
    with open(str(gt_path), "w", encoding="utf-8") as f:
        json.dump(ground_truth, f, ensure_ascii=False, indent=2)

    # ── summary ──
    print(f"\n  ✓ Generated {count} documents.")
    print(f"  ✓ Ground truth → {gt_path}")

    lvl_counts: dict[int, int] = {}
    for e in ground_truth:
        lvl_counts[e["degradation_level"]] = lvl_counts.get(e["degradation_level"], 0) + 1

    print(f"\n  Degradation distribution:")
    level_labels = [
        "0  perfect scan  ",
        "1  light noise   ",
        "2  noise+blur    ",
        "3  phone (good)  ",
        "4  phone (casual)",
        "5  poor quality  ",
    ]
    for lvl in range(6):
        cnt  = lvl_counts.get(lvl, 0)
        bar  = "▓" * round(cnt / count * 28)
        pct  = cnt / count * 100
        print(f"    Level {level_labels[lvl]}  {bar:<28}  {cnt:5d}  ({pct:4.1f}%)")

    type_counts: dict[str, int] = {}
    for e in ground_truth:
        type_counts[e["doc_type"]] = type_counts.get(e["doc_type"], 0) + 1
    print(f"\n  Document type distribution:")
    for dt, cnt in sorted(type_counts.items(), key=lambda x: -x[1]):
        pct = cnt / count * 100
        print(f"    {dt:<30}  {cnt:5d}  ({pct:4.1f}%)")

    q_scores = [e["quality_score"] for e in ground_truth]
    print(f"\n  Quality score — mean: {sum(q_scores)/len(q_scores):.1f}  "
          f"min: {min(q_scores)}  max: {max(q_scores)}")
    print()


# ─────────────────────────────────────────────────────────────────────────────
# SECTION 11 — CLI entry point
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        prog="generator.py",
        description="OCR Dataset Generator — Synthetic Polish Documents for PII Masking Tests",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python generator.py --count 1000 --output dataset
  python generator.py --count 500  --output test_set --seed 42
  python generator.py --count 100  --output quick    --no-balance

Output structure:
  <output>/
    images/
      doc_00000.png
      doc_00001.png
      ...
    ground_truth.json        ← entities + quality + degradation params per file
        """,
    )
    parser.add_argument(
        "--count", "-n",
        type=int,
        default=100,
        metavar="N",
        help="Documents to generate. Range: 1–5000  (default: 100)",
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="dataset",
        metavar="DIR",
        help="Output directory  (default: dataset)",
    )
    parser.add_argument(
        "--seed", "-s",
        type=int,
        default=None,
        metavar="SEED",
        help="Random seed for reproducibility  (default: random)",
    )
    parser.add_argument(
        "--no-balance",
        action="store_true",
        help="Disable equal distribution across degradation levels",
    )
    parser.add_argument(
        "--max-level",
        type=int,
        default=5,
        metavar="L",
        choices=range(6),
        help="Maksymalny poziom degradacji 0–5  (default: 5). "
             "Użyj --max-level 3 dla benchmarku (wyklucza obrazy nieczytelne dla OCR).",
    )
    parser.add_argument(
        "--measure-conf",
        action="store_true",
        help="Mierz rzeczywisty conf Tesseract per obraz (wolniej, ocr_conf_real w ground_truth)",
    )

    args = parser.parse_args()

    if not 1 <= args.count <= 5000:
        parser.error(f"--count must be in range 1–5000, got {args.count}")

    generate_dataset(
        count=args.count,
        out_dir=args.output,
        seed=args.seed,
        balanced=not args.no_balance,
        measure_conf=args.measure_conf,
        max_level=args.max_level,
    )


if __name__ == "__main__":
    main()
