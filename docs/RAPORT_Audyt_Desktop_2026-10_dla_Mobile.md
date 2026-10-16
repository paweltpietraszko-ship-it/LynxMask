# RAPORT — wnioski z audytu LynxMask Desktop dla wersji Mobile

_Data: 2026-10-16_  
_Autor: Claude Code (chmura), przy audycie Desktop (`LynxMask-Desktop`, gałąź `audyt/2026-10`)_  
_Baza Mobile: `master@450204b`. Kod Mobile **nie był zmieniany** — to tylko raport._  
_Pełne raporty Desktop: `arch/FINDING_2026-10_AUDYT.md`, `arch/FINDING_2026-10_MOBILE_PORT.md` w repo LynxMask-Desktop._

**Jak sprawdzone:** regexy skopiowane dosłownie z plików `.kt` i uruchomione na `java.util.regex` (JDK 21, ten sam silnik co Kotlin na JVM/Androidzie). Harness w sekcji 5. Testów gradle **nie uruchamiałem** (zgodnie z CLAUDE.md). Każdy punkt oznaczony **[S]** jest sprawdzony na poziomie regexu. Skutek w całym silniku oznaczony **[P]** trzeba potwierdzić na telefonie („Prawdziwy test = telefon”). Waga wg skali z MASTER §9 (P0 wyciek częściowy, P1 brak maskowania PII, P2 nadmaskowanie / utrata sensu, P3 kosmetyka).

---

## 1. Podsumowanie

| # | Sprawa | Waga | Typ |
|---|---|---|---|
| 1 | A.10 (tytuł + nazwisko) maskuje zwykłe słowa małą literą: „lek będzie podany”, „dr nie przyszedł” | P2 | bug |
| 2 | A.6 / IBAN_EARLY zjada pierwszą literę następnego słowa („…5387 **o**k”, „…2874 **b**ez”) | P2 | bug |
| 3 | CLEANUP „ogony” usuwa tekst bez dopisania do mapy → po depseudonimizacji fragment znika („-Kot” w nazwisku dwuczłonowym) | P2 | bug |
| 4 | A.12 maskuje „punkt nr 5.2”, gdy w 60 znakach wstecz jest „VAT” | P2 | bug |
| 5 | A.9c bierze za kwotę „3. sz” i „06.07” | P2/P3 | bug |
| 6 | A.8kw „KRS pod nr …” → śmieciowy token „KRS pod” | P3 | bug |
| 7 | `OutputGuard.kt:60` i `Deanonymizer.kt:22` mają `\d{3}\b`, a `TOKEN_RE` ma `(?!\d)` | P3 | niespójność |
| 8 | Depseudonimizacja: zwykłe `replace` po kluczach; nie przywraca tokenu odmienionego przez AI („OSOBY_001”, „Osoba_001”) | P2 | zalecenie |
| 9 | Kontrakt tokenu/taksonomii: MASTER §9 ≠ kod Mobile ≠ spec Desktop | — | **decyzja Pawła** |
| 10 | Morfeusz2 (offline) do czyszczenia słowników: kwalifikatory `imię`/`nazwisko`/`nazwa_pospolita` | — | zalecenie |

Dobra wiadomość: trzy rozwiązania Mobile zostały zmierzone na Desktop i dają tam największy zysk — słownik imion/nazwisk sprawdzany przed filtrami (`NameEngine.isAdjective`), `IBAN_EARLY` i `OCR_PHONE_AFTER_KW`. Szczegóły w raporcie portu Desktop.

---

## 2. Błędy w Mobile

### 2.1 A.10 — `(?i)` wyłącza wymóg wielkiej litery w nazwisku (P2)

- **Gdzie:** `AnchorEngine.kt:456-466` (regex), walidacja `:60-64`.
- **Co:** regex zaczyna się od `(?i)`, więc klasa `[A-ZŁŚŹĆŃĄĘÓŻ]` przy „nazwisku” pasuje też do małych liter. Komentarz (`:448`) i brief v2 §6.9 mówią „1–2 tokeny zaczynające się **wielką literą**”.
- **Dowód [S]:**
  - „Wypisany lek będzie podany rano” → dopasowanie `lek będzie podany`
  - „dr nie przyszedł dziś” → `dr nie przyszedł`
  - „Rozmawiałem z mgr inżynier wczoraj” → `mgr inżynier wczoraj`

  Żadne z tych słów nie jest w `OSOBA_DENYLIST` [S], więc walidacja ich nie blokuje.
- **Skutek [P]:** zwykłe zdania zamieniają się w `OSOBA_xxx` (np. w dokumentach medycznych „lek” = lekarstwo). Model AI traci sens.
- **Propozycja:** `(?i:` tylko na grupie tytułów, a część „nazwisko” bez flagi:
  `(?:(?i:dr)[^\S\n]+(?:(?i:hab)\.?[^\S\n]+)?|…)(?!…_)[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{1,30}…`.
  Osobno do decyzji: czy „lek” bez kropki ma być kotwicą (w Desktop ten sam przypadek dał FP „lek będzie wypisany”).

### 2.2 A.6 i IBAN_EARLY połykają pierwszą literę następnego słowa (P2)

- **Gdzie:** `AnchorEngine.kt:267-270` (A.6) i `StructuralEngine.kt:256` (`IBAN_EARLY`), ten sam kształt `PL[D \t\-]{20,42}`.
- **Co:** klasa obejmuje spację i litery cyfropodobne, a dopasowanie jest zachłanne, więc po ostatniej cyfrze bierze spację i pierwszą literę, jeśli jest z klasy D (o, l, i, s, b, z…).
- **Dowód [S]:**
  - „konto PL 27 1140 2004 0000 3002 0135 5387 ok” → `PL 27 1140 2004 0000 3002 0135 5387 o`
  - „na rachunek PL61109010140000071219812874 bez zwłoki” → `…2874 b`
- **Skutek [P]:** w wyniku zostaje „NUMER_001 k” / „NUMER_001 ez zwłoki”, a po przywróceniu pojawia się dodatkowa spacja.
- **Propozycja:** ostatni znak dopasowania musi być cyfrą lub cyfropodobnym, po którym nie stoi litera, np. dopisać `(?<=[0-9])(?![\p{L}])` albo zakończyć wzorzec na `[0-9OolIiSsBbZz](?![\p{L}])`. Jedno miejsce (`IBAN_EARLY`) + A.6.

### 2.3 CLEANUP ogonów gubi tekst przy depseudonimizacji (P2)

- **Gdzie:** `AnchorEngine.kt:698` — `Regex("""($tok)(?:[/\-.](?!$tok\b)[A-Za-z0-9]+)+""").replace(t) { it.groupValues[1] }`.
- **Co:** ogon („-Kot”, „/WAW”) jest wycinany z tekstu, ale **nie** dopisywany do wartości tokenu w `tokenMap`.
- **Dowód [S]:** „Przyjęła lek. OSOBA_001-Kot rano” → „Przyjęła lek. OSOBA_001 rano”. `OSOBA_001` w mapie nadal wskazuje na samo „Anna Wiśniewska” (przykład syntetyczny), więc po przywróceniu nazwisko traci drugi człon.
- **Propozycja:** w lambdzie dopisać ogon do wartości tokenu (`tokenMap[token] += ogon`). Wymaga przekazania mapy do `applyAnchorEngine` albo zwrócenia listy ogonów. To decyzja o interfejsie, mała zmiana.

### 2.4 A.12 — „Nr” z sygnałem „VAT” w oknie wstecz (P2)

- **Gdzie:** `AnchorEngine.kt:597-628` (sygnał `docSignalRe` `:606`).
- **Co:** komentarz `:583-584` mówi, że „Nr strony 5”, „punkt nr 5.2” NIE mają być maskowane. Gdy jednak w 60 znakach wstecz jest słowo „VAT”, sygnał dokumentu zapala się i „nr 5.2” zostaje zamaskowane.
- **Dowód [S]:** „Kwota VAT wynosi KWOTA_001, punkt nr 5.2 umowy” → dopasowanie `nr 5.2` + `\bvat\b` w lookback.
- **Propozycja:** wykluczyć „nr” poprzedzone słowem z listy struktury tekstu (`punkt|pkt|ust|strona|str|poz|§`) albo zawęzić okno do bieżącej linii.

### 2.5 A.9c — litery cyfropodobne w części dziesiętnej (P2/P3)

- **Gdzie:** `AnchorEngine.kt:423-426`.
- **Co:** pierwszy znak musi być cyfrą (BUG-A9C-ZERO-CYFR-FIX), ale dwa znaki po przecinku mogą być same literami z klasy D.
- **Dowód [S]:** „Punkt 3. sz kolejny” → `3. sz`; „dnia 06.07 spotkanie” → `06.07` (data jako kwota — znany kompromis z komentarza, ale „3. sz” już nie).
- **Propozycja:** w części dziesiętnej co najmniej jedna prawdziwa cyfra, np. `(?=[0-9OolIiSsBbZz]?[0-9])` przed `[0-9OolIiSsBbZz]{2}`.

### 2.6 A.8kw — „KRS pod nr …” (P3)

- **Gdzie:** `AnchorEngine.kt:358-361` (`\S+` = jedno słowo po kotwicy) vs `StructuralEngine.kt:494` (`\bKRS[^\S\n]*\d{10}\b`).
- **Dowód [S]:** „Spółka wpisana do KRS pod nr 0000123456” → strukturalny KRS nie pasuje (słowa „pod nr”). A.8kw bierze `KRS pod`. Sam numer łapie CATCHALL `\d{8,}` (`StructuralEngine.kt:723`), więc wynik to zbędny token „KRS pod” (P3). [P] Gdy OCR zniekształci numer (np. „00001234S6”), CATCHALL go nie złapie i numer zostanie jawny (KRS to identyfikator firmy, niska waga).
- **Propozycja:** w A.8kw dopuścić 0–2 słowa łączące (`pod|nr|numerem`) i wymagać ciągu cyfropodobnych na końcu, jak w A.5 dla NIP.

### 2.7 Niespójne wzorce tokenu (P3)

- `PseudonymEngine.kt:89`: `TOKEN_RE = \b(…)_(\d{3})(?!\d)`.
- `OutputGuard.kt:60` (`tokenRe`) i `Deanonymizer.kt:22` (`TOKEN_IN_TEXT`): `\b(?:…)_\d{3}\b`.
- **Dowód [S]:** „Zobacz OSOBA_001x tutaj” → `TOKEN_RE` znajduje `OSOBA_001`, a Guard nic. Guard nie zamienia takiego tokenu na `⟦TOSOBA⟧`, więc reguła „nazwisko obok tokenu OSOBA” (`OutputGuard.kt:170`) go nie widzi.
- **Propozycja:** jedna stała (np. `TOKEN_RE` z PseudonymEngine) używana we wszystkich trzech miejscach.

---

## 3. Zalecenia z audytu Desktop, które dotyczą też Mobile

### 3.1 Depseudonimizacja (P2, zalecenie)

`Deanonymizer.restore` (`Deanonymizer.kt:35-41`) to ta sama pętla `replace` po kluczach co w Desktop (`Depseudonimizuj.tsx`). W Desktop pomiar pokazał:
- tokeny zmienione przez model AI nie są przywracane: `OSOBY_001`, `Osoba_001`, `OSOBA 001`. Model odmieniający polskie zdania robi to realnie;
- przywracana jest pierwsza zapamiętana forma („Kowalskiemu”), więc w odpowiedzi AI („OSOBA_001 ma L4”) wychodzi zły przypadek;
- `replace` bez granic: przy ≥1000 tokenach jednego typu `OSOBA_100` uszkodziłby `OSOBA_1000` (w Mobile mało prawdopodobne, ale tani do zamknięcia).

Propozycja: przywracanie regexem z granicami zgodnymi z `TOKEN_RE`, dłuższe tokeny najpierw, opcjonalna tolerancja wielkości liter i odmiany kategorii. Tolerancja to kontrakt wspólny z Desktop — decyzja Pawła.

### 3.2 Morfeusz2 offline do czyszczenia słowników (zalecenie)

CLAUDE.md mówi: „Bug »silnik źle maskuje słowo« = zwykle jakość słownika… sprawdź Morfeusz2 na całym słowniku”. Morfeusz2 zwraca przy analizie kwalifikatory, które to ułatwiają [S, Morfeusz 1.99]:

| Słowo | Kwalifikatory Morfeusza |
|---|---|
| Bartłomiej | `imię` |
| Wiśniewskiej | `nazwisko` |
| piątku, niedzielę | tylko `nazwa_pospolita` |
| Osoba, Zapłata, Zając, Mazur | `nazwisko` **i** `nazwa_pospolita` (+ `nazwa_geograficzna`) |

Skrypt offline (jak `generate_city_surname_overlap.py` w `assets/`) mógłby automatycznie wypisać kandydatów kolizji nazwisko/słowo pospolite. Lista dla Pawła do decyzji — nie automatyczna blokada, zgodnie z decyzją 14.07 „nazwisko ma się maskować zawsze”. Zero nowej zależności w aplikacji.

### 3.3 Test szczelności na zdaniach z odmienionymi nazwiskami (zalecenie)

W Desktop sprawdziłem 36 krótkich zdań (6 przypadków gramatycznych, imiona z `names_inflected.json`, nazwiska z `surnames_top1000.json`, formy z Morfeusza). To dobry, tani test regresji także dla Mobile (StressBenchmark/`testy/`), szczególnie dla dopełniacza i narzędnika, gdzie Desktop gubił najwięcej. Skrypt: `audyt/rota_leak_test.py` w repo Desktop.

---

## 4. Decyzja Pawła — kontrakt tokenu i taksonomii

Trzy źródła mówią co innego:
- `MASTER_LynxMask_Mobile.md` §9 („ZAMKNIĘTE 20.06”): format `TYP_XXXXXX_NNN` (6 znaków heks na sesję), taksonomia 9 typów — FIRMA+INSTYTUCJA → ORGANIZACJA, plus TELEFON, SYGNATURA, DATA.
- Kod Mobile: `TYP_NNN` (`PseudonymEngine.kt:89,216`), 6 typów z FIRMA (`TOKEN_RE` bez ORGANIZACJA/TELEFON/SYGNATURA/DATA).
- Desktop `arch/spec.md` S2 (zamrożony 2026-10-16): `KATEGORIA_NNN`, taksonomia z FIRMA i INSTYTUCJA.

Wspólne zasady (`.lynxdict`, zgodność `TOKEN_RE`) wymagają jednej wersji. Nic nie zmieniałem.

---

## 5. Jak odtworzyć

**Harness Javy** (wzorce wklejone z `.kt` przez skrypt, `$D` → `[0-9OolIiSsBbZz]`):

```java
// ReTest.java — plik TSV: ID \t WZORZEC \t WEJŚCIE
import java.nio.file.*; import java.util.regex.*; import java.util.*;
public class ReTest { public static void main(String[] a) throws Exception {
  for (String line : Files.readAllLines(Path.of(a[0]))) {
    String[] p = line.split("\t", 3); Matcher m = Pattern.compile(p[1]).matcher(p[2]);
    List<String> h = new ArrayList<>(); while (m.find()) h.add("«" + m.group() + "»");
    System.out.println(p[0] + "  «" + p[2] + "» → " + h); } } }
```

Wynik (2026-10-16, JDK 21):

```
A.6            «konto PL 27 1140 2004 0000 3002 0135 5387 ok» → [«PL 27 1140 2004 0000 3002 0135 5387 o»]
IBAN_EARLY     «na rachunek PL61109010140000071219812874 bez zwłoki» → [«PL61109010140000071219812874 b»]
A.9c           «Punkt 3. sz kolejny» → [«3. sz»]
A.9c           «dnia 06.07 spotkanie» → [«06.07»]
A.10           «Wypisany lek będzie podany rano» → [«lek będzie podany»]
A.10           «dr nie przyszedł dziś» → [«dr nie przyszedł»]
A.10           «Rozmawiałem z mgr inżynier wczoraj» → [«mgr inżynier wczoraj»]
A.8kw          «Spółka wpisana do KRS pod nr 0000123456 w Sądzie» → [«KRS pod»]
KRS_STRUCT     (ten sam tekst) → []
A.12doc        «Kwota VAT wynosi KWOTA_001, punkt nr 5.2 umowy» → [«nr 5.2»]
CLEANUP_TAIL   «Przyjęła lek. OSOBA_001-Kot rano» → replace «Przyjęła lek. OSOBA_001 rano»
TOKEN_RE       «Zobacz OSOBA_001x tutaj» → [«OSOBA_001»]
GUARD_tokenRe  «Zobacz OSOBA_001x tutaj» → []
```

**Tekst do testu na telefonie** (wkleić jako osobny plik w `testy/`, sam tekst, bez nagłówków; dane syntetyczne):

```
Wypisany lek będzie podany rano. Doktor mówi, że dr nie przyszedł dziś.
Przelew na konto PL 27 1140 2004 0000 3002 0135 5387 ok, termin 7 dni.
Na rachunek PL61109010140000071219812874 bez zwłoki.
Kwota VAT wynosi 230,00 zł, punkt nr 5.2 umowy.
Punkt 3. sz kolejny.
Spółka wpisana do KRS pod nr 0000123456 w Sądzie Rejonowym.
Przyjęła lek. med. Anna Wiśniewska-Kot rano.
```

Oczekiwane po poprawkach: „lek będzie podany”, „dr nie przyszedł”, „ok”, „bez”, „punkt nr 5.2”, „3. sz” zostają jawne. IBAN-y, kwota i całe nazwisko „Wiśniewska-Kot” zamaskowane. Po depseudonimizacji nazwisko wraca w całości.
