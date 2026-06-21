#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
benchmark_mobile.py  v2.0
=========================
Automatyczny tester end-to-end LynxMask Mobile.
Analogiczny do desktop benchmark.py — testuje PRAWDZIWY silnik na urządzeniu.

Pipeline:
  1. Generuje dataset PNG + ground_truth.json (przez generator.py)
  2. Dla każdego PNG:
       adb push → /sdcard/lynxmask_bench/
       adb broadcast → BenchmarkReceiver w apce
       apka: ML Kit OCR → PseudonymEngine → wynik JSON
       adb pull ← result.json
  3. Porównuje wykryte tokeny z ground_truth
  4. Zapisuje raport (identyczny format jak desktop benchmark)

Wymagania:
  - Android z zainstalowaną apką LynxMask (z BenchmarkReceiver.kt)
  - USB debugging włączone, urządzenie podpięte
  - adb w PATH (lub podaj --adb-path)
  - generator.py w tym samym katalogu
  - pip install Pillow numpy

Uruchomienie:
  python benchmark_mobile.py --count 50
  python benchmark_mobile.py --count 150 --batch-size 50
  python benchmark_mobile.py --dataset dataset --no-generate
  python benchmark_mobile.py --count 50 --seed 42
  python benchmark_mobile.py --count 50 --adb-path "C:/platform-tools/adb.exe"

Tryb diagnostyczny (bez urządzenia — testuje Python port regexów):
  python benchmark_mobile.py --count 50 --mode text

Raporty w: benchmark_results/run_YYYYMMDD_HHMMSS/
  report.json  summary.txt  bugs.txt
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

# ─────────────────────────────────────────────────────────────────────────────
# Konfiguracja ADB
# ─────────────────────────────────────────────────────────────────────────────

ADB_ACTION      = "com.lynxmask.app.BENCHMARK"  # musi zgadzać się z BenchmarkReceiver.ACTION
APP_PACKAGE     = "com.lynxmask.app"
BENCH_DIR_DEV   = "/sdcard/Android/data/com.lynxmask.app/files/bench"  # katalog roboczy na urządzeniu
RESULT_JSON     = f"{BENCH_DIR_DEV}/result.json"
RESULT_DONE     = f"{BENCH_DIR_DEV}/result.done"
TIMEOUT_OCR_S   = 30    # max czas oczekiwania na wynik z apki (sekundy)
POLL_INTERVAL_S = 0.4   # jak często sprawdzać czy wynik gotowy
BATCH_PAUSE     = 2.0   # przerwa między paczkami

# ─────────────────────────────────────────────────────────────────────────────
# Mapowania i priorytety — identyczne jak desktop benchmark.py
# ─────────────────────────────────────────────────────────────────────────────

_ENTITY_TYPE_MAP = {
    "imie_nazwisko":             "OSOBA",
    "imie_nazwisko_nabywcy":     "OSOBA",
    "autor":                     "OSOBA",
    "osoba":                     "OSOBA",
    "pesel":                     "NUMER",
    "nip":                       "NUMER",
    "nip_sprzedawcy":            "NUMER",
    "nip_nabywcy":               "NUMER",
    "regon_sprzedawcy":          "NUMER",
    "telefon":                   "NUMER",
    "iban":                      "NUMER",
    "dowod_osobisty":            "NUMER",
    "numer_paszportu":           "NUMER",
    "numer_kw":                  "NUMER",
    "numer_faktury":             "NUMER",
    "numer_klienta":             "NUMER",
    "numer_umowy":               "NUMER",
    "sygnatura_akt":             "NUMER",
    "sygnatura_komornicza":      "NUMER",
    "sygnatura_administracyjna": "NUMER",
    "adres":                     "ADRES",
    "adres_nabywcy":             "ADRES",
    "email":                     "EMAIL",
    "data_urodzenia":            "NUMER",
}

_CRITICAL_KEYS = {
    "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy",
    "iban", "dowod_osobisty", "numer_paszportu",
}

# ─────────────────────────────────────────────────────────────────────────────
# ADB — komunikacja z urządzeniem
# ─────────────────────────────────────────────────────────────────────────────

_ADB: str = "adb"   # nadpisywane przez --adb-path


def _adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess:
    return subprocess.run(
        [_ADB, *args],
        capture_output=True, text=True, timeout=timeout,
    )


def check_device() -> str | None:
    """Zwraca serial podpiętego urządzenia lub None."""
    r = _adb("devices")
    lines = [l for l in r.stdout.splitlines()[1:] if l.strip() and "offline" not in l]
    for line in lines:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def push_file(local_path: Path, remote_name: str) -> bool:
    r = _adb("push", str(local_path), f"{BENCH_DIR_DEV}/{remote_name}")
    return r.returncode == 0


def send_broadcast(file_name: str) -> bool:
    r = _adb(
        "shell", "am", "broadcast",
        "-a", ADB_ACTION,
        "-e", "file", file_name,
        # Pełna nazwa klasy wymagana od Android 8 — skrót .BenchmarkReceiver
        # nie jest rozwijany przez system dla zewnętrznych nadawców (ADB).
        "-n", f"{APP_PACKAGE}/{APP_PACKAGE}.BenchmarkReceiver",
        "-f", "0x00000020",   # FLAG_RECEIVER_FOREGROUND — wyższy priorytet dostarczenia
    )
    return r.returncode == 0


def wait_for_result(timeout_s: float = TIMEOUT_OCR_S) -> bool:
    """Czeka aż apka zapisze sentinel result.done."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = _adb("shell", f"[ -f {RESULT_DONE} ] && echo yes || echo no")
        if r.returncode == 0 and "yes" in r.stdout:
            return True
        time.sleep(POLL_INTERVAL_S)
    return False


def pull_result(local_path: Path) -> bool:
    r = _adb("pull", RESULT_JSON, str(local_path))
    return r.returncode == 0


def cleanup_device() -> None:
    _adb("shell", f"rm -f {RESULT_JSON} {RESULT_DONE}")


def ensure_bench_dir() -> None:
    _adb("shell", f"mkdir -p {BENCH_DIR_DEV}")


def process_one_image(img_path: Path, tmp_result: Path) -> dict[str, Any]:
    """
    Pushuje jeden PNG na urządzenie, czeka na wynik, zwraca odparsowany JSON.
    Odpowiednik post_preview() z desktop benchmark.py.
    """
    cleanup_device()

    if not push_file(img_path, img_path.name):
        return {"bench_error": "adb push failed", "tokens": []}

    if not send_broadcast(img_path.name):
        return {"bench_error": "adb broadcast failed", "tokens": []}

    if not wait_for_result():
        return {"bench_error": f"Timeout ({TIMEOUT_OCR_S}s) — apka nie odpowiedziała", "tokens": []}

    if not pull_result(tmp_result):
        return {"bench_error": "adb pull failed", "tokens": []}

    try:
        with open(tmp_result, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        return {"bench_error": f"JSON parse error: {e}", "tokens": []}


# ─────────────────────────────────────────────────────────────────────────────
# Python port silnika regex — tylko do trybu --mode text (diagnostyka)
# ─────────────────────────────────────────────────────────────────────────────

_NAMES_MALE_FIRST = [
    "Adam","Andrzej","Bartłomiej","Bartosz","Cezary","Dariusz","Filip","Grzegorz",
    "Jakub","Jan","Kamil","Krzysztof","Łukasz","Maciej","Marek","Mariusz",
    "Michał","Paweł","Piotr","Radosław","Robert","Sebastian","Sławomir",
    "Stanisław","Tomasz","Wojciech","Zbigniew","Zygmunt",
]
_NAMES_FEMALE_FIRST = [
    "Agnieszka","Aleksandra","Alicja","Anna","Barbara","Beata","Dorota","Edyta",
    "Elżbieta","Ewa","Gabriela","Izabela","Joanna","Justyna","Katarzyna",
    "Krystyna","Magdalena","Małgorzata","Maria","Marta","Monika","Natalia",
    "Patrycja","Paulina","Sylwia","Teresa","Urszula","Zofia",
]
_NAMES_LAST = [
    "Adamczyk","Dudek","Jabłoński","Jabłońska","Jankowski","Jankowska",
    "Kaczmarek","Kowalski","Kowalska","Kowalczyk","Kozłowski","Kozłowska",
    "Kwiatkowski","Kwiatkowska","Lewandowski","Lewandowska","Malinowski",
    "Malinowska","Mazur","Nowak","Nowicki","Nowicka","Pawlak","Pawłowski",
    "Pawłowska","Piotrowski","Piotrowska","Sikora","Szymański","Szymańska",
    "Wiśniewski","Wiśniewska","Woźniak","Wróbel","Zając","Zawadzki",
    "Zawadzka","Ziółkowski","Ziółkowska","Zielińska","Krawczyk","Dąbrowski",
    "Dąbrowska",
]
_ALL_FIRST = sorted(set(_NAMES_MALE_FIRST + _NAMES_FEMALE_FIRST))
_NAMES_RE  = re.compile(
    r"\b(" + "|".join(re.escape(n) for n in _ALL_FIRST) + r")"
    r"\s+"
    r"(" + "|".join(re.escape(n) for n in sorted(set(_NAMES_LAST))) + r")\b",
    re.UNICODE,
)
_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("NUMER", re.compile(r"(?<!\d)\d{11}(?!\d)")),
    ("NUMER", re.compile(r"(?<!\d)\d{3}[-\s]\d{3}[-\s]\d{2}[-\s]\d{2}(?!\d)|(?<!\d)\d{10}(?!\d)")),
    ("NUMER", re.compile(r"(?<!\d)\d{9}(?!\d)")),
    ("NUMER", re.compile(r"PL\s?\d{2}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}|PL\d{26}", re.I)),
    ("NUMER", re.compile(r"[A-Z]{3}\s?\d{6}")),
    ("NUMER", re.compile(r"[A-Z]{2}\s?\d{7}")),
    ("NUMER", re.compile(r"(?:\+48|0048)[\s\-]?\d{3}[\s\-]?\d{3}[\s\-]?\d{3}|(?<!\d)\d{3}[\s\-]\d{3}[\s\-]\d{3}(?!\d)")),
    ("EMAIL", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")),
    ("ADRES", re.compile(r"(?:ul\.|al\.|os\.|pl\.)\s+[A-ZŁŚŹŻĆŃĄĘ][A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż\s\-]+\d+(?:[/\-]\d+)?", re.U)),
    ("NUMER", re.compile(r"[A-Z]{2}\d[A-Z]/\d{6}/\d/\d{3}")),
    ("NUMER", re.compile(r"(?:I{1,3}V?|VI{0,3}|[IVX]+)\s+[A-ZŁ]{1,3}\s+\d{2,5}/\d{2,4}")),
    ("NUMER", re.compile(r"[Kk]m\s+\d{1,5}/\d{2,4}")),
    ("NUMER", re.compile(r"[A-Z]{2}\.\d{6}\.\d{4}")),
    ("NUMER", re.compile(r"(?:FV|FVS|F|faktura\s+nr)\s*[/\-]?\s*\d{1,5}[/\-]\d{1,2}[/\-]\d{2,4}|(?:FV|FVS|F)\d{4}/\d{2}/\d{4}|\d{4}/\d{2}/\d{4}", re.I)),
    ("NUMER", re.compile(r"KL-\d{5}")),
    ("NUMER", re.compile(r"(?<!\d)\d{2}[.\-]\d{2}[.\-]\d{4}(?!\d)")),
]

def _run_text_engine(text: str) -> list[dict[str, str]]:
    found: list[dict[str, str]] = []
    seen: set[str] = set()
    def _add(orig: str, typ: str) -> None:
        key = orig.strip()
        if key and key not in seen:
            seen.add(key)
            found.append({"original": key, "type": typ, "token": ""})
    for m in _NAMES_RE.finditer(text):
        _add(m.group(0), "OSOBA")
    for typ, pat in _PATTERNS:
        for m in pat.finditer(text):
            _add(m.group(0), typ)
    return found


# ─────────────────────────────────────────────────────────────────────────────
# Analiza — identyczna logika jak desktop benchmark.py
# ─────────────────────────────────────────────────────────────────────────────

def _norm(v: str) -> str:
    return v.replace(" ", "").replace("-", "").lower()

# Usuwa prefiks klucza takie jak "PESEL: ", "NIP: " itp. z tokenu silnika.
# Silnik kontekstowy maskuje całe dopasowanie (np. "PESEL: 860202037006"),
# a benchmark porównuje tylko wartości liczbowe z ground truth.
_LABEL_PREFIX_RE = re.compile(r'^[A-Za-z0-9ąćęłńóśźżĄĆĘŁŃÓŚŹŻ ]{2,15}[:\s\-–]{1,3}')

def _norm_strip_label(v: str) -> str:
    """Normalizuje token po usunięciu ewentualnego prefiksu kluczowego."""
    return _norm(_LABEL_PREFIX_RE.sub('', v.strip()))

def _fuzzy_match_numeric(a: str, b: str) -> bool:
    if len(a) < 9 or len(b) < 9:
        return False
    if abs(len(a) - len(b)) > 1:
        return False
    longer  = a if len(a) >= len(b) else b
    shorter = a if len(a) < len(b) else b
    if len(longer) == len(shorter):
        return sum(x != y for x, y in zip(longer, shorter)) <= 1
    for i in range(len(longer)):
        if longer[:i] + longer[i+1:] == shorter:
            return True
    return False


def analyze(
    gt: dict[str, Any],
    resp: dict[str, Any],
) -> dict[str, Any]:

    if resp.get("bench_skipped"):
        return {
            "file":          gt["file"],
            "doc_type":      gt.get("doc_type", "?"),
            "quality_score": gt.get("quality_score", -1),
            "deg_level":     gt.get("degradation_level", -1),
            "ocr_text_len":  0,
            "ocr_conf":      resp.get("ocr_conf"),
            "ocr_chars":     resp.get("ocr_chars", 0),
            "bench_error":   None,
            "bench_skipped": True,
            "tokens_found":  [],
            "false_positives": [],
            "entities":      [],
            "summary": {
                "total_entities": 0, "detected": 0, "missed": 0,
                "critical_missed": 0, "false_positives": 0,
                "recall": None, "precision": None, "f1": None,
            },
        }

    result: dict[str, Any] = {
        "file":          gt["file"],
        "doc_type":      gt.get("doc_type", "?"),
        "quality_score": gt.get("quality_score", -1),
        "deg_level":     gt.get("degradation_level", -1),
        "ocr_text_len":  len(resp.get("ocr_text", "")),
        "ocr_conf":      resp.get("ocr_conf"),
        "bench_error":   resp.get("bench_error"),
        "tokens_found":  resp.get("tokens", []),
        "false_positives": [],
        "entities":      [],
        "summary":       {},
    }

    gt_entities: dict[str, Any] = gt.get("entities", {})
    gt_norms    = [_norm(str(v)) for v in gt_entities.values()]
    tokens      = resp.get("tokens", [])
    orig_norms  = [_norm(str(t.get("original", ""))) for t in tokens]
    # Wersje bez prefiksu kontekstowego ("PESEL: 860202..." → "860202...")
    orig_norms_stripped = [_norm_strip_label(str(t.get("original", ""))) for t in tokens]

    total = detected = critical_missed = 0
    for key, val in gt_entities.items():
        val_n   = _norm(str(val))
        is_crit = key in _CRITICAL_KEYS
        found   = False

        if not resp.get("bench_error"):
            for on in orig_norms + orig_norms_stripped:
                if val_n == on:
                    found = True; break
                if len(val_n) >= 6 and (val_n in on or on in val_n):
                    found = True; break
                if _fuzzy_match_numeric(val_n, on):
                    found = True; break

        total += 1
        if found:
            detected += 1
        elif is_crit:
            critical_missed += 1

        result["entities"].append({
            "key":           key,
            "value":         str(val),
            "expected_type": _ENTITY_TYPE_MAP.get(key, "?"),
            "detected":      found,
            "critical":      is_crit,
            "type_mismatch": (
                found and
                _ENTITY_TYPE_MAP.get(key) is not None and
                not any(
                    _norm(str(t.get("original", ""))) == val_n and
                    t.get("type", "").startswith(_ENTITY_TYPE_MAP.get(key, "?"))
                    for t in resp.get("tokens", [])
                )
            ),
        })

    false_positives: list[dict] = []
    for tok in resp.get("tokens", []):
        orig        = str(tok.get("original", ""))
        orig_n      = _norm(orig)
        orig_n_bare = _norm_strip_label(orig)  # bez prefiksu "PESEL: " itp.
        if not orig_n or len(orig_n) < 2:
            continue
        matched = any(
            (orig_n == gn or (len(orig_n) >= 6 and (orig_n in gn or gn in orig_n)) or
             orig_n_bare == gn or (len(orig_n_bare) >= 6 and (orig_n_bare in gn or gn in orig_n_bare)))
            for gn in gt_norms
        )
        if not matched:
            false_positives.append(tok)

    result["false_positives"] = false_positives

    missed    = total - detected
    tp        = detected
    fp        = len(false_positives)
    precision = round(tp / (tp + fp), 3) if (tp + fp) > 0 else None
    recall    = round(detected / total, 3) if total > 0 else None
    f1 = (round(2 * precision * recall / (precision + recall), 3)
          if precision is not None and recall is not None and (precision + recall) > 0
          else None)

    result["summary"] = {
        "total_entities":  total,
        "detected":        detected,
        "missed":          missed,
        "critical_missed": critical_missed,
        "false_positives": fp,
        "recall":          recall,
        "precision":       precision,
        "f1":              f1,
    }
    return result


# ─────────────────────────────────────────────────────────────────────────────
# Generowanie datasetu
# ─────────────────────────────────────────────────────────────────────────────

def generate_dataset(count: int, out_dir: str, seed: int | None, generator_path: str | None = None) -> Path:
    if generator_path:
        gen = Path(generator_path)
    else:
        gen = Path(__file__).parent / "generator.py"
    if not gen.exists():
        print(f"[BŁĄD] Nie znaleziono generator.py: {gen}")
        print(f"       Podaj ścieżkę przez --generator-path")
        sys.exit(1)
    cmd = [sys.executable, str(gen), "--count", str(count), "--output", out_dir]
    if seed is not None:
        cmd += ["--seed", str(seed)]
    print(f"\n[GEN] Generuję {count} dokumentów → {out_dir}")
    r = subprocess.run(cmd, check=False)
    if r.returncode != 0:
        print(f"[BŁĄD] generator.py zakończył się błędem (kod {r.returncode})")
        sys.exit(1)
    return Path(out_dir)


# ─────────────────────────────────────────────────────────────────────────────
# Główna pętla
# ─────────────────────────────────────────────────────────────────────────────

def run_benchmark(
    dataset_dir: Path,
    run_dir: Path,
    batch_size: int,
    mode: str,
) -> list[dict[str, Any]]:

    gt_path = dataset_dir / "ground_truth.json"
    if not gt_path.exists():
        print(f"[BŁĄD] Brak {gt_path}")
        sys.exit(1)

    with open(gt_path, encoding="utf-8") as f:
        ground_truth: list[dict] = json.load(f)

    images_dir = dataset_dir / "images"
    all_results: list[dict[str, Any]] = []
    total   = len(ground_truth)
    batches = (total + batch_size - 1) // batch_size
    tmp_result = run_dir / "_tmp_result.json"

    if mode == "adb":
        ensure_bench_dir()
        print(f"\n[BENCH] Dokumentów: {total}  |  Paczki: {batches} × {batch_size}")
        print(f"[BENCH] Tryb: ADB end-to-end (ML Kit + PseudonymEngine)\n")
    else:
        print(f"\n[BENCH] Dokumentów: {total}  |  Paczki: {batches} × {batch_size}")
        print(f"[BENCH] Tryb: text (Python regex — diagnostyka bez urządzenia)\n")

    for batch_no in range(batches):
        b_start = batch_no * batch_size
        b_end   = min(b_start + batch_size, total)
        batch   = ground_truth[b_start:b_end]

        print(f"[PACZKA {batch_no+1}/{batches}]  dok. {b_start+1}–{b_end}")
        print("─" * 72)

        batch_results: list[dict] = []

        for i, gt_entry in enumerate(batch, 1):
            img_path = images_dir / Path(gt_entry["file"]).name
            if not img_path.exists():
                print(f"  [{i:3d}] BRAK: {img_path.name}")
                continue

            if mode == "adb":
                resp = process_one_image(img_path, tmp_result)
            else:
                # tryb text — diagnostyczny
                gt_entities = gt_entry.get("entities", {})
                text = " ".join(str(v) for v in gt_entities.values())
                tokens = _run_text_engine(text)
                resp = {"tokens": tokens, "ocr_text": text, "ocr_conf": None}

            result = analyze(gt_entry, resp)
            batch_results.append(result)

            if result.get("bench_skipped"):
                conf_s = (f"conf={result['ocr_conf']*100:.0f}%"
                          if result["ocr_conf"] else "conf=N/A")
                print(f"  [{i:3d}] {img_path.name:<20} {result['doc_type']:<22} "
                      f"lvl={result['deg_level']} [SITO] odrzucony"
                      f" ({conf_s}, chars={result['ocr_chars']})")
            else:
                s      = result["summary"]
                rec    = f"{s['recall']*100:.0f}%"    if s["recall"]    is not None else " N/A"
                prec   = f"{s['precision']*100:.0f}%" if s["precision"] is not None else " N/A"
                fp_str = f" FP:{s['false_positives']}"     if s["false_positives"] > 0 else ""
                crit   = f" ⚠CRIT:{s['critical_missed']}"  if s["critical_missed"]  else ""
                err    = f" ERR:{result['bench_error']}"    if result["bench_error"] else ""
                conf_s = (f" conf={result['ocr_conf']:.0f}%"
                          if result["ocr_conf"] is not None
                          else f" qs={result['quality_score']}")

                print(f"  [{i:3d}] {img_path.name:<20} {result['doc_type']:<22} "
                      f"lvl={result['deg_level']}{conf_s:<12} "
                      f"R={rec} P={prec}{fp_str}{crit}{err}")

        all_results.extend(batch_results)
        _save_partial(all_results, run_dir, batch_no + 1)
        _print_batch_summary(batch_results, batch_no + 1)

        if batch_no < batches - 1:
            print(f"\n[PAUZA] {BATCH_PAUSE:.0f}s...\n")
            time.sleep(BATCH_PAUSE)

    return all_results


# ─────────────────────────────────────────────────────────────────────────────
# Raportowanie — identyczny format jak desktop benchmark.py
# ─────────────────────────────────────────────────────────────────────────────

def _print_batch_summary(results: list[dict], batch_no: int) -> None:
    if not results:
        return
    active  = [r for r in results if not r.get("bench_skipped")]
    skipped = len(results) - len(active)
    total_ent  = sum(r["summary"]["total_entities"]  for r in active)
    total_det  = sum(r["summary"]["detected"]        for r in active)
    total_fp   = sum(r["summary"]["false_positives"] for r in active)
    total_crit = sum(r["summary"]["critical_missed"] for r in active)
    errors     = sum(1 for r in active if r["bench_error"])
    recall     = total_det / total_ent if total_ent > 0 else 0
    precision  = total_det / (total_det + total_fp) if (total_det + total_fp) > 0 else 0
    skip_s     = f"  sito={skipped}" if skipped else ""
    print(f"\n  ── Paczka {batch_no} ──  "
          f"recall={recall*100:.1f}%  precision={precision*100:.1f}%  "
          f"FP={total_fp}  crit_miss={total_crit}  err={errors}{skip_s}\n")


def build_report(results: list[dict[str, Any]], run_dir: Path, mode: str) -> None:
    with open(run_dir / "report.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    skipped_results = [r for r in results if r.get("bench_skipped")]
    active_results  = [r for r in results if not r.get("bench_skipped")]

    total_docs = len(results)
    skipped_count = len(skipped_results)
    total_ent  = sum(r["summary"]["total_entities"]  for r in active_results)
    total_det  = sum(r["summary"]["detected"]        for r in active_results)
    total_miss = total_ent - total_det
    total_crit = sum(r["summary"]["critical_missed"] for r in active_results)
    total_fp   = sum(r["summary"]["false_positives"] for r in active_results)
    errors     = [r for r in active_results if r["bench_error"]]
    recall_all = total_det / total_ent if total_ent > 0 else 0
    tp_fp      = total_det + total_fp
    prec_all   = total_det / tp_fp if tp_fp > 0 else 0
    f1_all     = (2 * prec_all * recall_all / (prec_all + recall_all)
                  if (prec_all + recall_all) > 0 else 0)
    ts         = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    per_type: dict[str, dict[str, int]] = {}
    for r in active_results:
        for e in r["entities"]:
            et = e["expected_type"]
            per_type.setdefault(et, {"detected": 0, "total": 0, "fp": 0})
            per_type[et]["total"] += 1
            if e["detected"]:
                per_type[et]["detected"] += 1
        for fp in r["false_positives"]:
            et = fp.get("type", "?")
            per_type.setdefault(et, {"detected": 0, "total": 0, "fp": 0})
            per_type[et]["fp"] += 1

    per_level: dict[int, dict[str, int]] = {}
    for r in active_results:
        lv = r["deg_level"]
        per_level.setdefault(lv, {"detected": 0, "total": 0, "docs": 0})
        per_level[lv]["docs"]     += 1
        per_level[lv]["total"]    += r["summary"]["total_entities"]
        per_level[lv]["detected"] += r["summary"]["detected"]

    L = [
        "BENCHMARK LYNXMASK MOBILE — RAPORT",
        f"Data:  {ts}",
        f"Run:   {run_dir}",
        f"Tryb:  {mode}",
        "═" * 60, "",
        "OGÓLNE",
        f"  Dokumentów łącznie:            {total_docs}",
        f"  Odrzuconych przez sito OCR:    {skipped_count}",
        f"  Przetworzonych przez silnik:   {len(active_results)}",
        f"  Encji w ground truth:          {total_ent}",
        f"  Wykrytych i zamaskowanych:     {total_det}",
        f"  Pominiętych (false neg):       {total_miss}",
        f"  False positives (overmasking): {total_fp}",
        f"  RECALL    (czy znalazł co powinien):    {recall_all*100:.1f}%",
        f"  PRECISION (czy nie zamaskował za dużo): {prec_all*100:.1f}%",
        f"  F1        (harmoniczna):                {f1_all*100:.1f}%",
        f"  Krytyczne braki:               {total_crit}  (PESEL/NIP/IBAN/dowód/paszport)",
        f"  Błędy timeout/ADB:             {len(errors)}",
        "",
        "  INTERPRETACJA:",
        "  Recall < 95%  → pipeline gubi encje — ryzyko wycieku danych",
        "  Precision < 90% → pipeline maskuje za dużo — dokument traci użyteczność",
        "",
        "RECALL / PRECISION PER TYP ENCJI",
    ]
    for et, c in sorted(per_type.items()):
        rc   = c["detected"] / c["total"] if c["total"] > 0 else 0
        fp_t = c.get("fp", 0)
        pr   = c["detected"] / (c["detected"] + fp_t) if (c["detected"] + fp_t) > 0 else 1.0
        bar  = "█" * int(rc * 16) + "░" * (16 - int(rc * 16))
        L.append(f"  {et:<12} R={rc*100:5.1f}% P={pr*100:5.1f}%  [{bar}]  "
                 f"({c['detected']}/{c['total']}  FP={fp_t})")

    L += ["", "RECALL PER POZIOM DEGRADACJI"]
    lvl_labels = {0: "perfect scan  ", 1: "light noise   ", 2: "noise+blur    ",
                  3: "phone (good)  ", 4: "phone (casual)", 5: "poor quality  "}
    for lv in sorted(per_level):
        c   = per_level[lv]
        rc  = c["detected"] / c["total"] if c["total"] > 0 else 0
        bar = "█" * int(rc * 20) + "░" * (20 - int(rc * 20))
        L.append(f"  Lvl {lv} {lvl_labels.get(lv,'')} [{bar}] {rc*100:5.1f}%  "
                 f"docs={c['docs']} ent={c['total']}")

    # Korelacja quality_score vs recall (tylko aktywne — skipped nie mają recall)
    pairs = [(r["quality_score"], r["summary"]["recall"])
             for r in active_results if r["summary"]["recall"] is not None]
    if pairs:
        L += ["", "KORELACJA quality_score (syntetyczny) vs recall end-to-end"]
        buckets: dict[str, list[float]] = {}
        for qs, rc in pairs:
            key = f"qs={int(qs)//10*10:02d}-{int(qs)//10*10+9:02d}"
            buckets.setdefault(key, []).append(rc)
        for bk in sorted(buckets):
            vals = buckets[bk]
            avg  = sum(vals) / len(vals)
            L.append(f"  {bk}  → avg recall={avg*100:.1f}%  (n={len(vals)})")
        L += ["", "  Duży spadek recall dla niskich qs → OCR gubi encje na słabych skanach."]

    with open(run_dir / "summary.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")

    B = [f"BUGS / ANOMALIE — {ts}", f"Tryb: {mode}. Do przekazania instancji naprawczej.",
         "═" * 60, ""]

    if skipped_results:
        B += [f"[SITO] {skipped_count} dokumentów odrzuconych przez sito jakości OCR", ""]
        for r in skipped_results:
            conf_s = f"conf={r['ocr_conf']*100:.0f}%" if r["ocr_conf"] else "conf=N/A"
            B.append(f"  {r['file']}  lvl={r['deg_level']}  {conf_s}  chars={r.get('ocr_chars',0)}")
        B.append("")

    crit_cases = [(r, e) for r in active_results for e in r["entities"]
                  if not e["detected"] and e["critical"]]
    if crit_cases:
        B += [f"[KRYTYCZNE] Pominięte encje wysokiego ryzyka: {len(crit_cases)}", ""]
        for r, e in crit_cases[:40]:
            conf_s = f"conf={r['ocr_conf']:.0f}%" if r["ocr_conf"] else f"qs={r['quality_score']}"
            B.append(f"  {r['file']}  lvl={r['deg_level']}  {conf_s}"
                     f"  {e['key']}={e['value'][:35]}")
        if len(crit_cases) > 40:
            B.append(f"  ... i {len(crit_cases)-40} więcej — patrz report.json")
        B.append("")

    if total_fp > 0:
        B += [f"[FALSE POSITIVES] Zamaskowano {total_fp} encji spoza ground truth", ""]
        worst = sorted(active_results, key=lambda r: r["summary"]["false_positives"], reverse=True)[:10]
        for r in worst:
            fp_count = r["summary"]["false_positives"]
            if fp_count == 0: break
            B.append(f"  {r['file']}  lvl={r['deg_level']}  FP={fp_count}")
            for fp in r["false_positives"][:4]:
                B.append(f"    → [{fp['type']}] \"{fp['original'][:40]}\"  ({fp.get('token','')})")
        B.append("")

    low_recall = [(et, c) for et, c in per_type.items()
                  if c["total"] >= 5 and c["detected"] / c["total"] < 0.80]
    if low_recall:
        B += ["[RECALL<80%] Typy z niskim recall (min. 5 próbek):", ""]
        for et, c in sorted(low_recall, key=lambda x: x[1]["detected"] / x[1]["total"]):
            rc = c["detected"] / c["total"]
            B.append(f"  {et:<12} {rc*100:.1f}%  ({c['detected']}/{c['total']})")
        B.append("")

    if errors:
        B += [f"[ERRORS] Timeouty / błędy ADB: {len(errors)}", ""]
        for r in errors[:10]:
            B.append(f"  {r['file']} → {r['bench_error']}")
        B.append("")

    type_mismatches = [
        (r, e) for r in active_results for e in r["entities"]
        if e.get("type_mismatch")
    ]
    if type_mismatches:
        B += [f"[TYPE MISMATCH] Wykryte z błędnym typem: {len(type_mismatches)}", ""]
        B.append("  Encja zamaskowana ale pod złym tokenem (np. PESEL jako ADRES).")
        for r, e in type_mismatches[:20]:
            B.append(f"  {r['file']}  {e['key']}={e['value'][:30]}  oczekiwano={e['expected_type']}")
        B.append("")

    if not (skipped_results or crit_cases or low_recall or errors or type_mismatches):
        B.append("Brak krytycznych anomalii. Recall ogólny OK.")

    with open(run_dir / "bugs.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(B) + "\n")

    print(f"\n{'═'*60}")
    print(f"BENCHMARK ZAKOŃCZONY — {ts}")
    print(f"  Tryb:            {mode}")
    print(f"  Dokumentów:      {total_docs}  (sito odrzuciło: {skipped_count})")
    print(f"  Recall:          {recall_all*100:.1f}%")
    print(f"  Precision:       {prec_all*100:.1f}%")
    print(f"  F1:              {f1_all*100:.1f}%")
    print(f"  False positives: {total_fp}")
    print(f"  Krytyczne braki: {total_crit}")
    print(f"  Błędy ADB:       {len(errors)}")
    print(f"  Raporty: {run_dir}")
    print(f"    report.json  summary.txt  bugs.txt")
    print("═" * 60)


def _save_partial(results: list[dict], run_dir: Path, batch_no: int) -> None:
    with open(run_dir / f"partial_batch_{batch_no:02d}.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    global _ADB

    parser = argparse.ArgumentParser(
        prog="benchmark_mobile.py",
        description="Tester end-to-end LynxMask Mobile — prawdziwy ML Kit + PseudonymEngine przez ADB",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Przykłady:
  python benchmark_mobile.py --count 50
  python benchmark_mobile.py --count 150 --batch-size 50
  python benchmark_mobile.py --dataset dataset --no-generate
  python benchmark_mobile.py --count 50 --seed 42
  python benchmark_mobile.py --count 50 --adb-path "C:/platform-tools/adb.exe"
  python benchmark_mobile.py --count 50 --mode text   (diagnostyka bez urządzenia)
        """,
    )
    parser.add_argument("--count",      "-n", type=int, default=50)
    parser.add_argument("--batch-size", "-b", type=int, default=50)
    parser.add_argument("--dataset",    "-d", type=str, default=None)
    parser.add_argument("--no-generate",      action="store_true")
    parser.add_argument("--seed",       "-s", type=int, default=None)
    parser.add_argument("--mode",             type=str, default="adb",
                        choices=["adb", "text"],
                        help="adb = prawdziwy silnik na urządzeniu (domyślny), "
                             "text = Python regex bez urządzenia (diagnostyka)")
    parser.add_argument("--generator-path",  type=str, default=None,
                        help=r"Ścieżka do generator.py, np. C:\Users\Pawel\Desktop\pseudominizer\generator.py")
    parser.add_argument("--adb-path",         type=str, default="adb",
                        help="Ścieżka do adb.exe (domyślnie: adb z PATH)")
    args = parser.parse_args()
    _ADB = args.adb_path

    # Sprawdź urządzenie (tylko tryb ADB)
    if args.mode == "adb":
        print(f"\n[CHECK] Szukam urządzenia ADB...")
        device = check_device()
        if device is None:
            print("[BŁĄD] Brak podpiętego urządzenia. Sprawdź USB debugging.")
            print("       Lub użyj --mode text do diagnostyki bez urządzenia.")
            sys.exit(1)
        print(f"[CHECK] OK — urządzenie: {device}\n")

    ts      = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = Path("benchmark_results") / f"run_{ts}"
    run_dir.mkdir(parents=True, exist_ok=True)
    print(f"[RUN]   → {run_dir}")

    if args.no_generate and args.dataset:
        dataset_dir = Path(args.dataset)
        if not dataset_dir.exists():
            print(f"[BŁĄD] Dataset nie istnieje: {dataset_dir}")
            sys.exit(1)
    else:
        dataset_dir = generate_dataset(
            count=args.count,
            out_dir=f"dataset_{ts}",
            seed=args.seed,
            generator_path=args.generator_path,
        )

    results = run_benchmark(
        dataset_dir=dataset_dir,
        run_dir=run_dir,
        batch_size=args.batch_size,
        mode=args.mode,
    )
    build_report(results, run_dir, args.mode)


if __name__ == "__main__":
    main()
