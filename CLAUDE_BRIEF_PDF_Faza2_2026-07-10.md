# Brief: PDF round-trip (faza 2) — dla Claude Code

**Data:** 10.07.2026 (zaktualizowano 10.07 wieczór — zmiana architektury DOCX)
**Autor ustaleń:** Paweł + Cursor (sesja 09–10.07), aktualizacja: Paweł + Claude (10.07)
**Gałąź docelowa:** po domknięciu `feature/document-export` (DOCX gotowy)
**Status:** plan architektury — gotowy do startu

---

## AKTUALIZACJA 10.07 wieczór — WAŻNE, czyta się PRZED resztą tego briefu

Ten brief poniżej (sekcja "PDF-A — natywny PDF") zakładał **alignment raw↔normalized**
jako wspólny fundament z DOCX (bbox, zachowanie pozycji tekstu w warstwie PDF). **Ten
fundament nie powstał i nie jest już potrzebny** — Paweł zdecydował (10.07), że DOCX
round-trip nie odwzorowuje oryginalnego formatowania wcale: eksport zapisuje gotowy,
zamaskowany tekst jako świeży, minimalny plik (jeden akapit na linię), bez szukania
niczego w oryginale. Uzasadnienie: pełne odwzorowanie layoutu to poziom "chmurowej
konkurencji", zbędny dla lokalnej appki mobile — ważne żeby tekst z tokenami dał się
otworzyć i edytować, nie żeby wyglądał identycznie jak oryginał.

**Konsekwencja dla PDF-A (ten brief):** ten sam, dużo prostszy wzorzec. Wyciągnij CAŁY
tekst z warstwy PDF (bez bbox/pozycji), zamaskuj przez `PseudonymEngine`, zapisz jako
nowy, minimalny PDF z tekstem w akapitach (Android `PdfDocument` + `Canvas.drawText`,
łamanie linii proste). Sekcje niżej o bbox/segmentach/alignment dla PDF-A są **NIEAKTUALNE
w tej części** — zostawione jako kontekst historyczny, nie plan do wykonania.

**PDF-B (skan) BEZ ZMIAN** — to inny mechanizm (obraz + OCR + prostokąty), opisany niżej,
nie dotyczy dzisiejszej decyzji.

**Excel (nowy wątek, 10.07):** ten sam minimalistyczny wzorzec pasuje też do XLSX —
wyciągnąć tekst komórek, zamaskować, zapisać jako świeży, minimalny xlsx (jeden arkusz,
wartości bez formuł/formatowania). Nie było jeszcze ocenione przez Cursora — traktować
jako pomysł do zweryfikowania, nie gotowy plan.

---

## Decyzja właściciela

1. ~~Maksymalnie co się da: ten sam format na wyjściu co na wejściu z zachowaniem layoutu~~
   — **ZMIENIONE 10.07:** ten sam format tak, layout NIE. Minimalistyczny, edytowalny plik
   wystarczy dla mobile (patrz aktualizacja wyżej). Desktop może celować wyżej.
2. **DOCX round-trip zrobiony** (10.07, wersja minimalistyczna) — PDF następny.
3. **„PDF → DOCX → user robi PDF w Wordzie”** to skrót na proste teksty, **nie** zamiennik PDF z PDF dla skanów/urzędów.
4. **Interaktywność (odkryj token)** zostaje **w aplikacji** — nie w pliku PDF (odrzucona propozycja Kimi: AcroForm + Adobe JS + PII w metadanych pliku).
5. **Model bezpieczeństwa LynxMask:** mapa tokenów w SQLCipher/sesji — **nigdy** osadzać oryginałów PII w eksporcie pliku.

---

## Jak robi konkurencja (chmura, 2025–2026)

Wspólny wzorzec rynku — **nie jeden algorytm, dwa potoki:**

```
PDF wejściowy
    ├─ warstwa tekstu (natywny PDF)  →  podmiana / placeholdery w strukturze pliku
    └─ skan / brak tekstu            →  bbox na stronie + czarny prostokąt (lub blur)
Wyjście: PDF (ten sam format)
```

| Produkt | Wejście | Wyjście | Tryby |
|---------|---------|---------|-------|
| [Re-Doc](https://re-doc.com/) | PDF, skan, DOCX, obraz | **Ten sam format** | Czarne boxy (nieodwracalne) **lub** syntetyczne zamienniki (szukalne) |
| [DocIQ Shield](https://dociq.io/shield) | DOCX, natywny PDF | DOCX→DOCX, PDF→PDF | Placeholdery (struktura) lub pełna redakcja; OOXML-level dla Worda |
| [Redacto](https://redacto.co/) | PDF, Word, skany, PPT… | Ten sam typ pliku | Przegląd przed redakcją, certyfikat audytu, metadane |
| [Cleardox](https://cleardox.io/) | głównie PDF | PDF | Pseudonimy lub czarna redakcja, bulk |
| [Docuflair](https://www.docuflair.com/) | 100+ formatów | głównie PDF/PDF-A | Pseudonimizacja odwracalna (tabela zamian) pod AI |

**Czego konkurencja NIE robi:** PDF w → tylko DOCX out jako główny produkt. **Nie polega** na JavaScript w Adobe Reader do odkrywania PII.

**Co odłożyć na późno (nawet u nich):** syntetyczne zamienniki z idealnym zachowaniem fontu/układu (Re-Doc) — drogie; na mobile realistyczne są boxy + tokeny wizualne, nie „idealny layout engine”.

---

## Co LynxMask już ma (nie zaczynać od zera)

| Komponent | Plik | Rola przy PDF |
|-----------|------|----------------|
| OCR PDF | `DocumentExtractor.kt` — `ocrFromPdfUri`, `PdfRenderer` | Wejście: strony → bitmapa → ML Kit |
| Redakcja obrazu | `ImageRedactionPipeline.kt` — bbox, `applyRedactions` | **Rdzeń PDF-B (skan)** — per strona |
| Silnik maskowania | `PseudonymEngine.kt` | Ten sam dla tekstu z PDF |
| Wzorzec artefaktu | `DocumentArtifact.kt` — DOCX teraz, PDF faza 2 w komentarzu | **`PdfArtifact` + `PdfWriter`** — ten sam kontrakt co DOCX |
| Eksport DOCX | `DocxExtractor` / `DocxWriter` | Wzorzec do skopiowania: extractor → plainText + pozycje → writer |
| Benchmark | `BenchmarkInstrumentedTest.kt` | Mierzy **OCR na PNG**, nie DOCX — PDF skan ≈ ten potok |

---

## Dwa potoki PDF (obowiązkowy fork)

### PDF-A — natywny PDF (warstwa tekstu)

**Blisko DOCX round-trip.**

**NIEAKTUALNE (10.07) — patrz aktualizacja na górze pliku.** Zamiast poniższego: wyciągnij
płaski tekst (bez bbox), zamaskuj, zapisz jako nowy minimalny PDF z tekstem w akapitach —
dokładnie jak DOCX. Zostawione jako historyczny kontekst:

1. ~~Ekstrakcja: tekst **+ współrzędne** słów/linii (bbox, strona, fontSize opcjonalnie).~~
2. ~~`plainText` + segmenty → `PseudonymEngine` (jak DOCX).~~
3. ~~**Alignment raw↔norm** — ten sam problem co DOCX; rozwiązanie wspólne (`tokenRawRanges`, `maskingBaseText`).~~
4. ~~Writer podmienia tekst w PDF (placeholdery / tokeny), zachowuje layout w miarę możliwości.~~

**Research przed kodem (1 sesja, bez implementacji):**
- PDFBox lub Pdfium na JVM/Android — czy wyciąga bbox polskiego tekstu z pliku testowego.
- Porównać z tym, co obiecuje DocIQ („native text-layer processing”).

**Zależności:** unikać iText AGPL na mobile bez decyzji właściciela; preferować lekkie biblioteki lub Android `PdfDocument` tam, gdzie wystarczy.

### PDF-B — skan / PDF bez sensownej warstwy tekstu

**Rozszerzenie `ImageRedactionPipeline` na wielostronicowość.**

```
PdfRenderer (strona N) → bitmapa
    → ML Kit OCR + bbox (już jest w ImageRedactionPipeline)
    → PseudonymEngine na plainText (globalnie lub per strona)
    → mapowanie tokenów → prostokąty na bitmapie
    → applyRedactions (zamalowanie / blur)
    → złożenie stron w nowy PDF (Android PdfDocument)
```

**Wyjście:** PDF **rastrowy** (jak skan po redakcji) — akceptowalne dla urzędu; konkurencja robi to samo na złych skanach („pixel-perfect black boxes”, Re-Doc).

**Nie:** Tess4j, AcroForm z PII w `userName`, Adobe JavaScript (propozycja Kimi — **odrzucona** w ocenie Cursor 09.07).

---

## Relacja do DOCX i inputProvenance

| Temat | Kolejność |
|-------|-----------|
| Alignment raw↔norm | **Bloker wspólny** DOCX + PDF-A — najpierw |
| `inputProvenance` (czysty tekst vs OCR) | Po alignment; PDF-B zawsze OCR, PDF-A/DOCX często nie |
| Eksport „DOCX z dowolnego wejścia” | Opcjonalny skrót (generator nowego Worda z tekstu) — **nie** zamiennik PDF-B |
| PDF z PDF | Po alignment + zamkniętym DOCX round-trip na telefonie |

**Benchmark:** lvl0–3 to zawsze PNG → ML Kit OCR. **Nie** imituje DOCX/TXT. Osobny zestaw testów (esej, umowa Word) poza benchmarkem obrazowym.

---

## Kontrakt API (szkic — jak `DocxArtifact`)

```kotlin
// Faza 2 — nie tworzyć przed alignment DOCX
internal sealed interface DocumentArtifact  // już jest
internal data class PdfArtifact(
    val sourceBytes: ByteArray,           // oryginał lub strumień stron
    val plainText: String,
    val isScan: Boolean,                  // fork A vs B
    val segments: List<PdfTextSegment>,   // PDF-A: bbox + strona
    val pageBitmaps: List<...>?,          // PDF-B: opcjonalnie cache renderu
    val sourceUri: Uri? = null,
) : DocumentArtifact

internal data class PdfTextSegment(
    val page: Int,
    val bbox: RectF,                      // współrzędne PDF/px
    val plainStart: Int,
    val plainEnd: Int,
    val text: String,
)
```

Writer:
- **PDF-A:** podmiana w warstwie tekstu (research biblioteki).
- **PDF-B:** `PdfDocument` + `Canvas.drawBitmap` po `applyRedactions`.

Eksport w UI: przycisk „Zapisz PDF” analogiczny do „Zapisz DOCX” — tylko gdy `artifact is PdfArtifact` i brak `hasUnhandledParts` / fail-closed.

---

## Co NIE robić (antywzorce)

1. **Nie** wdrażać propozycji Kimi (iText+JS+sqlite-jdbc+Tess4j+PII w pliku).
2. **Nie** zakładać, że PDF→DOCX zastąpi PDF→PDF dla klientów prawnych/skany.
3. **Nie** zaczynać PDF przed **alignment DOCX** (test 45 znaków Pawła, `Wizja Caude 2.docx`, MissingTokens).
4. **Nie** dodawać regexów pod benchmark lvl0 — benchmark ≠ DOCX/proza.
5. **Nie** mieszać metryk IMAGE-REDACT z recall tekstowym PDF-A.

---

## Kolejność prac (dla Claude)

| Krok | Zadanie | Gate |
|------|---------|------|
| ~~0~~ | ~~Alignment DOCX~~ — ZBĘDNE, DOCX poszedł minimalistyczną ścieżką (10.07) | — |
| 1 | Research: prosty sposób wyciągnięcia płaskiego tekstu z natywnego PDF na Androidzie (PDFBox-Android / Pdfium / inne) — bez bbox, tylko tekst | raport, bez commitu silnika |
| 2 | `PdfWriter` PDF-A (tekst): `PseudonymEngine` → nowy minimalny PDF (`PdfDocument` + `Canvas.drawText`, akapity jak w DOCX) | test JVM/parser + telefon |
| 3 | Research PDF-B: 1 strona `PdfRenderer` → redakcja → `PdfDocument` zapis | proof-of-concept w testach androidTest lub ręcznie |
| 4 | `PdfArtifact` + parser (fork isScan) | test JVM/parser |
| 5 | `PdfWriter` PDF-B (skan) — drugi produkt PDF | telefon: skan 1–3 strony |
| 6 | UI „Zapisz PDF”, fail-closed | telefon |
| 7 | (opcjonalnie, osobna ocena) Excel: ten sam wzorzec minimalistyczny na xlsx | po PDF |

---

## Pliki do dotknięcia (przewidywalnie)

| Plik | Zmiana |
|------|--------|
| `document/DocumentArtifact.kt` | `PdfArtifact`, `PdfTextSegment` |
| `document/PdfExtractor.kt` | nowy — fork scan vs text |
| `document/PdfWriter.kt` | nowy — A i/lub B |
| `DocumentExtractor.kt` | opcjonalnie: reuse `ocrFromPdfUri` + bbox |
| `ImageRedactionPipeline.kt` | PDF-B: mapowanie token→rect wielostronicowe |
| `IncomingDocumentFlow.kt` | `artifact` dla PDF, `onSavePdf` |
| `PseudonymResultPanel.kt` | przycisk „Zapisz PDF” |
| `PseudonymEngine.kt` | `inputProvenance`, wspólny alignment z DOCX |

**Nie modyfikować:** `OutputGuard.kt` bez potrzeby; AnchorEngine pod PDF osobno.

---

## Testy (po rozpoczęciu fazy 2)

1. **JVM:** parser `PdfArtifact` na minimalnym PDF z warstwą tekstu (fixture w `src/test/resources/`).
2. **androidTest:** 1 strona skanu — prostokąty zamaskowane, plik PDF otwiera się w czytniku.
3. **Telefon (Paweł):** ten sam flow co DOCX — wejście PDF, podgląd tokenów, zapis, brak jawnych PII w pliku.
4. **Regresja:** benchmark obrazowy bez zmian KPI; PDF nie w `ground_truth_lvl03.json` na start.

---

## Źródła w repo

- `CURSOR_BRIEF_AddressEngine_2026-07-04.md` — wzorzec briefu
- `memory/project_architecture.md` — mapa warstw (jeśli aktualna)
- `TODO.md` — alignment DOCX jako priorytet przed PDF
- `MASTER_LynxMask_Mobile.md` — redakcja PDF jako osobny ticket (faza 0 out of scope)
- Ocena Kimi (Cursor 09.07) — w transkrypcie sesji document-export; werdykt: nie wdrażać

---

## Jedno zdanie dla sesji startowej Claude

**PDF = dwa produkty (tekst vs skan), ten sam format out, ten sam wzorzec co DOCX (`Artifact` + `Writer`), interaktywność w apce; najpierw domknij alignment DOCX, potem research bbox, potem PDF-B (skan) jako pierwszy działający eksport PDF.**
