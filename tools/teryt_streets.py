#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
teryt_streets.py — Pobiera nazwy ulic z GUS TERYT i generuje street_names.json
dla LynxMask (app/src/main/assets/)

Uruchomienie:
    pip install zeep requests
    python teryt_streets.py

Wynik: street_names.json w bieżącym katalogu
Skopiuj do: LynxMask/app/src/main/assets/street_names.json
"""

import json
import io
import zipfile
import sys
from collections import Counter
from pathlib import Path

# ─────────────────────────────────────────────────────────────
# Konfiguracja
# ─────────────────────────────────────────────────────────────
TOP_N       = 8000   # ile najpopularniejszych nazw zachować
MIN_LENGTH  = 4      # ignoruj nazwy krótsze niż 4 znaki (np. "Ku")
OUTPUT_FILE = "street_names.json"

# Publiczne dane testowe GUS — bez rejestracji
TERYT_WSDL  = "https://uslugaterytws1test.stat.gov.pl/wsdl/terytws1.wsdl"
TERYT_USER  = "TestPubliczny"
TERYT_PASS  = "1234abcd"

# Bezpośredni URL do pliku CSV (strona GUS, zmienia się co kwartał)
TERYT_CSV_URL = "https://eteryt.stat.gov.pl/eTeryt/rejestr_teryt/udostepnianie_danych/baza_teryt/uzytkownicy_indywidualni/pobieranie/ULIC.zip"

# ─────────────────────────────────────────────────────────────
# Metoda 1 — SOAP API (zeep)
# ─────────────────────────────────────────────────────────────
def fetch_via_soap():
    """
    Pobiera ulice przez SOAP API GUS TERYT.
    Metoda: PobierzKatalogULIC() — zwraca wszystkie ulice naraz.
    Wymaga: pip install zeep
    """
    try:
        from zeep import Client
        from zeep.wsse.username import UsernameToken
    except ImportError:
        print("  zeep nie zainstalowany. Uruchom: pip install zeep")
        return None

    print("  Łączenie z API TERYT (SOAP)...")
    try:
        token  = UsernameToken(TERYT_USER, TERYT_PASS)
        client = Client(wsdl=TERYT_WSDL, wsse=token)
        print("  Pobieranie katalogu ulic — może potrwać 30-60 sekund...")
        result = client.service.PobierzKatalogULIC()
        print(f"  Pobrano {len(result)} rekordów.")
        return result
    except Exception as e:
        print(f"  SOAP nie powiodło się: {e}")
        return None


def parse_soap_result(result):
    names = []
    for row in result:
        name = getattr(row, 'NAZWA_1', None) or getattr(row, 'Nazwa1', None) or ''
        name = str(name).strip()
        if len(name) >= MIN_LENGTH:
            names.append(name.lower())
    return names


# ─────────────────────────────────────────────────────────────
# Metoda 2 — Bezpośrednie pobieranie ZIP z CSV
# ─────────────────────────────────────────────────────────────
def fetch_via_zip():
    """
    Pobiera ULIC.zip ze strony GUS i parsuje plik CSV.
    Plik CSV ma kolumny (separator ;):
    WOJ;POW;GMI;RODZ_GMI;SYM;SYM_UL;CECHA;NAZWA_1;NAZWA_2;STAN_NA
    Interesuje nas kolumna NAZWA_1.
    """
    import urllib.request

    print(f"  Pobieranie ULIC.zip z GUS ({TERYT_CSV_URL})...")
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': '*/*',
        'Referer': 'https://eteryt.stat.gov.pl/',
    }
    try:
        req  = urllib.request.Request(TERYT_CSV_URL, headers=headers)
        resp = urllib.request.urlopen(req, timeout=60)
        data = resp.read()
        print(f"  Pobrano {len(data)/1024:.0f} KB.")
        return data
    except Exception as e:
        print(f"  ZIP nie powiodło się: {e}")
        return None


def parse_zip_csv(zip_data):
    names = []
    try:
        with zipfile.ZipFile(io.BytesIO(zip_data)) as zf:
            csv_files = [n for n in zf.namelist() if n.upper().endswith('.CSV')]
            print(f"  Pliki w ZIP: {csv_files}")
            for csv_name in csv_files:
                with zf.open(csv_name) as f:
                    content = f.read().decode('utf-8', errors='replace')
                    lines   = content.splitlines()
                    # Znajdź indeks kolumny NAZWA_1
                    header  = lines[0].split(';')
                    try:
                        idx = header.index('NAZWA_1')
                    except ValueError:
                        # Spróbuj wariant adresowy
                        idx_candidates = [i for i, h in enumerate(header) if 'NAZWA' in h.upper()]
                        idx = idx_candidates[0] if idx_candidates else 7  # fallback: 8. kolumna
                    print(f"  Kolumna NAZWA_1 = indeks {idx} (nagłówek: {header})")
                    for line in lines[1:]:
                        parts = line.split(';')
                        if len(parts) > idx:
                            name = parts[idx].strip()
                            if len(name) >= MIN_LENGTH:
                                names.append(name.lower())
    except Exception as e:
        print(f"  Błąd parsowania ZIP: {e}")
    return names


# ─────────────────────────────────────────────────────────────
# Metoda 3 — Lokalny plik (manual fallback)
# ─────────────────────────────────────────────────────────────
def fetch_from_local():
    """
    Jeśli obie metody automatyczne zawiodły — użyj ręcznie pobranego pliku.

    Instrukcja ręczna:
    1. Wejdź na: https://eteryt.stat.gov.pl/
    2. Baza TERYT → Pobieranie → ULIC → pobierz ZIP
    3. Skopiuj ULIC.csv lub ULIC_Adresowy.csv do tego samego folderu co skrypt
    """
    for filename in ['ULIC.csv', 'ULIC_Adresowy.csv', 'ulic.csv']:
        p = Path(filename)
        if p.exists():
            print(f"  Znaleziono lokalny plik: {filename}")
            names = []
            content = p.read_text(encoding='utf-8', errors='replace')
            lines   = content.splitlines()
            header  = lines[0].split(';')
            idx_candidates = [i for i, h in enumerate(header) if 'NAZWA_1' in h.upper() or 'NAZWA1' in h.upper()]
            idx = idx_candidates[0] if idx_candidates else 7
            for line in lines[1:]:
                parts = line.split(';')
                if len(parts) > idx:
                    name = parts[idx].strip()
                    if len(name) >= MIN_LENGTH:
                        names.append(name.lower())
            return names
    return None


# ─────────────────────────────────────────────────────────────
# Budowanie JSON
# ─────────────────────────────────────────────────────────────
def build_json(names, top_n):
    """
    Zlicza częstość nazw. Najczęstsze = najpopularniejsze w Polsce
    (Lipowa, Różana, Polna powtarzają się w każdej gminie → wysokie n).

    Format wyjściowy identyczny jak surnames_top1000.json:
    { "lipowa": [], "różana": [], ... }
    Wartość pusta lista — LookupTables.kt traktuje klucze jako mianowniki.
    """
    counter = Counter(names)
    top     = counter.most_common(top_n)

    print(f"\nTop 15 najczęstszych nazw (= najpopularniejsze w Polsce):")
    for name, count in top[:15]:
        print(f"  {name:<30} ({count}x w bazie)")

    result = {name: [] for name, _ in top}
    return result


# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────
def main():
    print("=" * 55)
    print("  LynxMask — generator bazy nazw ulic z TERYT GUS")
    print("=" * 55)

    names = None

    # Próba 1: SOAP API
    print("\n[1/3] Próba SOAP API GUS TERYT...")
    soap_result = fetch_via_soap()
    if soap_result:
        names = parse_soap_result(soap_result)
        print(f"  Wyciągnięto {len(names)} nazw z SOAP.")

    # Próba 2: ZIP HTTP
    if not names:
        print("\n[2/3] Próba bezpośredniego pobierania ZIP...")
        zip_data = fetch_via_zip()
        if zip_data:
            names = parse_zip_csv(zip_data)
            print(f"  Wyciągnięto {len(names)} nazw z CSV.")

    # Próba 3: lokalny plik
    if not names:
        print("\n[3/3] Szukam lokalnego pliku ULIC.csv...")
        names = fetch_from_local()
        if names:
            print(f"  Wyciągnięto {len(names)} nazw z lokalnego pliku.")

    if not names:
        print("\n❌ Wszystkie metody zawiodły.")
        print("\nCo zrobić ręcznie:")
        print("  1. Wejdź na https://eteryt.stat.gov.pl/")
        print("  2. Baza TERYT → Pobieranie → ULIC → pobierz ZIP")
        print("  3. Wypakuj ULIC.csv do folderu z tym skryptem")
        print("  4. Uruchom skrypt ponownie")
        sys.exit(1)

    print(f"\nUnikalne nazwy (przed filtrem): {len(set(names))}")

    result = build_json(names, TOP_N)

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    size_kb = Path(OUTPUT_FILE).stat().st_size / 1024
    print(f"\n✅ Zapisano {len(result)} nazw ulic → {OUTPUT_FILE} ({size_kb:.0f} KB)")
    print(f"\nSkopiuj do:")
    print(f"  LynxMask/app/src/main/assets/street_names.json")
    print(f"\nNastępnie w LookupTables.kt dodaj:")
    print(f"  _streetForms = loadFormsFromAsset(context, \"street_names.json\")")


if __name__ == "__main__":
    main()
