# Raport audytu — Bezpieczeństwo i RODO (LynxMask Mobile)

**Data audytu:** 2026-06-25  
**Audytor:** Cursor (read-only, przegląd kodu)  
**Brief źródłowy:** `BRIEF_Cursor_Security_RODO.md`  
**Zakres:** `app/src/main/java/com/lynxmask/app/` + manifest + gradle + testy powiązane

---

## Werdykt ogólny

| Twierdzenie produktowe | Status |
|---|---|
| Aplikacja **offline** (brak wysyłki PII przez własny kanał sieciowy) | **Potwierdzone w kodzie** — brak `INTERNET`, brak HTTP clientów |
| Sesje **szyfrowane lokalnie** | **Potwierdzone** — SQLCipher + AES-256-GCM na blobach |
| **Brak wycieków PII** | **Nie w pełni** — znaleziono 8 problemów RODO/SECURITY, 2 bugi logiczne, 3 obszary NIEZNANE |

---

## Metodologia

Przegląd statyczny kodu: logowanie, storage, eksport, schowek, manifest, zależności Gradle, pipeline silnika (pkt 11). Bez uruchamiania aplikacji na urządzeniu — miejsca oznaczone **NIEZNANE** wymagają testu runtime.

Format werdyktu z briefu: **OK** / **PROBLEM [RODO|SECURITY|BUG|ARCHITEKTURA]** / **NIEZNANE**.

---

## 1. LOGOWANIE PII

### Pytania z briefu

| Pytanie | Werdykt |
|---|---|
| `Log.*` / `println` / `System.out` w `app/src/main/java/` | Przeszukano — **brak** `println`/`System.out`. `Log.*` w ~12 plikach (szczegóły poniżej) |
| DebugLogBuffer — surowy OCR / pseudonimizacja / tokenMap? | **Mieszane** — v1.1 RODO-safe w głównych metodach, wyjątki w ShareTarget |
| `clearOnExit()` podpięte do lifecycle? | **Tak** — `onDestroy` (nie `onStop`) |
| Timber / Firebase / Crashlytics / Sentry w gradle? | **Brak** |

### Werdykty

**OK** — `DebugLogBuffer.log()` (`DebugLogBuffer.kt:76`)  
`if (!BuildConfig.DEBUG) return` — w release bufor RAM i Logcat z tego kanału są nieaktywne.

**OK** — `DebugLogBuffer.logPseudonymResult()` / `logOcrAnalysis()` v1.1  
Bez surowego OCR, bez wartości `tokenMap`, bez `flag.fragment`. Sekcja `[5]` loguje tekst **pseudonimizowany** (tokeny zamiast PII) — patrz wyjątek przy pkt 10.

**OK** — `PseudonymEngine.kt:168–169`  
Log nazwisk (`PSE_OSOBA`) tylko w `BuildConfig.DEBUG`.

**OK** — `SessionStore.kt`, `ClipboardCheckActivity.kt`  
Logi operacyjne: sesja_id, liczniki, akcje — bez treści dokumentów.

**PROBLEM [RODO/SECURITY]** — `ShareTargetActivity.kt:568`  
```kotlin
DebugLogBuffer.log("ShareTarget", rawText.take(300).replace("\n", "↵"))
```
Pierwsze **300 znaków surowego OCR** trafia do bufora DEBUG i Logcat (`LynxMask_Debug`). Bezpośrednie PII.

**PROBLEM [RODO]** — `ShareTargetActivity.kt:405`, `:409`  
Przy „Zapamiętaj” / allowlist: log `'$word'` / `'$value'` — oryginalne fragmenty PII (DEBUG).

**PROBLEM [RODO]** — `DebugLogBuffer.kt:204–212`  
Sekcja `[5]` kopiuje `pseudonymizedText`. Gdy numer telefonu **nie** trafił do tokenMap (pkt 10), surowy numer może trafić do „Kopiuj logi”.

**PROBLEM [ARCHITEKTURA]** — `LynxMaskLogger.kt`  
Martwy kod (brak wywołań w projekcie), ale implementuje logowanie **pełnego** OCR, tokenMap i tekstu wyjściowego w DEBUG. Ryzyko przy przypadkowym podpięciu.

**OK** — `clearOnExit()`  
`MainActivity.onDestroy():75`, `ShareTargetActivity.onDestroy():108`.  
**Uwaga:** brak wywołania w `onStop()` — bufor żyje gdy aplikacja idzie w tło bez destroy procesu.

---

## 2. PLIKI TYMCZASOWE

**OK** — OCR tekstowy nie persistowany  
`ShareTargetActivity` czyta URI przez `contentResolver.openInputStream()` — tekst OCR tylko w RAM. Bitmapy po OCR: `bitmap.recycle()` (`:711`, `:760`).

**OK** — brak kopii odebranego pliku w cache  
Share nie zapisuje oryginału do `cacheDir` — stream bezpośrednio z URI.

**OK** — oryginalny obraz przed redakcją  
Tylko `Bitmap` w pamięci (`ImageRedactionPipeline`, `ShareTargetActivity` OCR) — nie zapisywany na dysk.

**PROBLEM [RODO/SECURITY]** — `ImageRedactionPipeline.kt:85–92`  
Zredagowany JPEG: `cacheDir/redacted_images/redacted_${timestamp}.jpg`.  
**Brak kasowania** po share — pliki kumulują się (dostęp: prywatny cache aplikacji + FileProvider z `grantUriPermissions` dla odbiorcy share).

**NIEZNANE** — cleanup po `onActivityResult` share  
Kod nie rejestruje callbacku kasującego plik po udostępnieniu — wymaga testu runtime czy Android czyści grant po zamknięciu choosera.

**PROBLEM [ARCHITEKTURA]** — `BenchmarkService.kt:102–103`  
Zapisuje `result.json` z `ocr_text` i `tokens[].original` (PII) do `getExternalFilesDir("bench")`. Narzędzie deweloperskie — nie flow użytkownika (MASTER: BUG-BENCH-PUBLIC).

---

## 3. SESSIONSTORE — kryptografia

**OK** — algorytm  
- Baza: **SQLCipher** (`SessionStore.kt:458–466`)  
- Bloby `token_map_enc`, `masked_text_enc`: **AES-256-GCM** (`:498–512`, transformacja `AES/GCM/NoPadding`)

**OK** — pochodzenie kluczy  
- Passphrase SQLCipher: 32 B `SecureRandom`, szyfrowany AES-GCM w Android Keystore (`KEYSTORE_ALIAS`, `:517–536`)  
- Klucz blobów: `KeyGenParameterSpec` AES-256-GCM, Keystore (`:479–495`)  
- Hasło UI: PBKDF2-HMAC-SHA256, 310k iter. (`LoginScreen.kt:152–163`) — **osobny** mechanizm od SessionStore

**OK** — IV/nonce  
Unikalny IV per `encryptBytes()` — `cipher.iv` prepended do blobu (`:501–503`). Brak reużycia.

**PROBLEM [SECURITY/ARCHITEKTURA]** — `app/build.gradle.kts:87`  
`androidx.security:security-crypto:1.1.0-alpha06` (AUD-M06) — wersja **alpha**. Dotyczy `UserDictionary` / `GuardAllowlist` (`EncryptedFile`), nie SessionStore.

**OK** — zawartość sessionData  
| Pole | Zawartość |
|---|---|
| `token_map_enc` | JSON mapy **token → oryginał** (PII) — szyfrowany AES-GCM |
| `masked_text_enc` | Tekst **po** pseudonimizacji (tokeny, nie oryginały) — szyfrowany |
| `responses.content` | Plaintext wewnątrz bazy SQLCipher (depseudo / odpowiedzi AI) |
| Surowy OCR przed pseudonimizacją | **Nie** persistowany w SessionStore |

**OK** — backup wyłączony  
`allowBackup=false`, `fullBackupContent=false`, `data_extraction_rules.xml` wyklucza wszystkie domeny.

**OK** — Art. 17  
`SessionStore.deleteAllData()` + UI w `MainActivity` SecurityModal.

---

## 4. UPRAWNIENIA SIECIOWE — offline?

**OK** — `AndroidManifest.xml`  
**Brak** `android.permission.INTERNET`.

**OK** — `app/build.gradle.kts`  
Brak Retrofit, OkHttp, Volley, Firebase, analytics.

**OK** — ML Kit Text Recognition `16.0.1`  
Model **on-device** (bundled w APK/AAB).

**OK** — ML Kit Face Detection `16.1.7`  
Przetwarzanie lokalne (`ImageRedactionPipeline.kt:31–38`) — brak wywołań HTTP w kodzie aplikacji.

**NIEZNANE** — `play-services-mlkit-document-scanner:16.0.0-beta1`  
GMS Document Scanner — typowo on-device via Play Services. Pierwsze uruchomienie **może** wymagać pobrania komponentu przez Play Services (poza permission `INTERNET` aplikacji). Brak jawnego uploadu obrazu w kodzie LynxMask.

---

## 5. EKSPORT PLIKÓW

**PROBLEM [RODO]** — `.lynxdict` (`UserDictionary.exportToJson()` + `MainActivity.kt:395–408`)  
- Format: **plaintext JSON** `[{"value":"Jan Kowalski","type":"OSOBA"}, …]`  
- Zawiera **oryginalne PII** wpisane przez użytkownika  
- Celowe (backup słownika), ląduje w **Downloads** (MediaStore API 29+)  
- Brak dodatkowego dialogu ostrzegawczego poza opisem w UI SecurityModal

**OK** — eksport pseudonimizacji (share / copy dokumentu)  
`PseudonymResultPanel.kt:115` — `canSend = canAct && redHits.isEmpty()` blokuje copy i forward przy RED Guard (`:385`, `:212`).

**OK** — tekst i tokenMap **nie** eksportowane razem  
Eksport share = tylko `outputText` (maskowany). tokenMap zostaje w szyfrowanej bazie.

**OK** — tokenMap nie w pliku `.lynxdict`  
`.lynxdict` = słownik użytkownika, nie mapa sesji. Rekonstrukcja PII z `.lynxdict` możliwa tylko dla wpisów użytkownika, nie dla całego dokumentu.

**PROBLEM [RODO]** — `DepseudonymizationScreen.kt:370–381`  
„Pobierz plik” eksportuje **pełny odkryty tekst** (PII) przez `CreateDocument` — **bez ostrzeżenia** (BUG-EXPORT-DEPSEUDO, MASTER status 🔲).

**OK** — potwierdzenie użytkownika przy eksporcie pseudonimizacji  
Share wymaga akcji użytkownika (chooser). Copy zablokowane przy RED.

---

## 6. CLIPBOARD

**OK** — brak kopiowania **surowego** OCR / tekstu przed pseudonimizacją  
Główny flow: `PseudonymResultPanel` kopiuje `outputText` (maskowany + ewentualnie świadomie odkryte tokeny).

**OK** — copy/forward zablokowane przy RED  
`canSend = false` gdy `redHits.isNotEmpty()` (`PseudonymResultPanel.kt:115`).

**PROBLEM [RODO]** — `ClipboardCheckActivity.kt:134–137`  
Warunek „schowek czysty”: `flags.isEmpty() && riskScore == GREEN` — **nie sprawdza `guardHits`**.  
Scenariusz: telefon niewykryty przez silnik, Guard też nie złapie → fałszywe „Schowek wygląda bezpiecznie”.

**PROBLEM [RODO]** — `ClipboardCheckActivity.kt:172–175`  
„Zastąp schowek bezpieczną wersją” wkleja `pseudonymizedText`, który **nie usuwa** fragmentów złapanych tylko przez OutputGuard (Guard flaguje, nie modyfikuje tekstu). Użytkownik może wkleić tekst z gołym numerem telefonu.

**OK** — `LibraryScreen.kt:395`  
Kopiowanie `response.content` — świadoma akcja użytkownika na zapisanej odpowiedzi depseudo (PII zamierzone).

**OK** — brak `ClipboardManager.setPrimaryClip()` na surowym wejściu OCR  
Compose `LocalClipboardManager.setText()` — tylko przetworzone / maskowane / depseudo teksty.

---

## 7. DEPSEUDONYMIZACJA

**OK** — przechowywanie tokenMap  
`SessionStore.save()` — JSON mapy w kolumnie `token_map_enc`, szyfrowany AES-GCM (`SessionStore.kt:134–163`).

**OK** — szyfrowanie  
Cały JSON tokenMap w jednym blobie (nie osobne pliki). `masked_text_enc` osobny blob.

**PROBLEM [RODO]** — BUG-EXPORT-DEPSEUDO  
Eksport odkrytego tekstu do pliku użytkownika bez confirm dialog — patrz pkt 5.  
**Nie** eksportuje tokenMap — eksportuje już **odszyfrowany tekst** (PII w plaintext).

**OK** — audit_log  
Zero PII — tylko `sesja_id` + akcja (`SessionStore.kt:294–305`).

---

## 8. IMAGE REDACTION — RODO obrazów

**OK** — face detection lokalnie  
ML Kit on-device (`ImageRedactionPipeline.kt:31–38`).

**OK** — oryginalny obraz nie persistowany  
Tylko RAM; po sesji zależy od GC.

**PROBLEM [SECURITY]** — cache JPEG  
Patrz pkt 2 — brak cleanup `redacted_images/`.

**OK** — FileProvider scope  
`AndroidManifest.xml:151–158` — `exported=false`, `grantUriPermissions=true`, ścieżka `cache-path/redacted_images/` (`file_paths.xml`). Dostęp dla innej aplikacji tylko przez tymczasowy grant URI przy share.

**OK** — `FLAG_SECURE` w release  
`ShareTargetActivity.kt:97` — blokuje screenshoty (poza DEBUG).

---

## 9. UPRAWNIENIA

**OK** — `uses-permission`  
Tylko `READ_MEDIA_IMAGES` (`AndroidManifest.xml:30`).

**OK** — brak legacy storage  
Brak `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`.

**OK** — `ClipboardCheckActivity` — `exported=false`  
**OK** — `BenchmarkService` — `exported=false`

**ARCHITEKTURA (akceptowalne)** — `ShareTargetActivity` `exported=true`  
Wymagane dla share sheet / ACTION_VIEW. Powierzchnia: złośliwy intent z URI — aplikacja przetwarza przekazany content lokalnie.

**OK** — `LynxMaskTileService` `exported=true`  
Chronione `android.permission.BIND_QUICK_SETTINGS_TILE`.

**OK** — `MainActivity` `exported=true`  
Launcher — standard.

---

## 10. S10 — TELEFON nie w tokenMap

### Potwierdzenie z kodu

**OK** — typowe formaty telefonów → token `NUMER_*`  
Wzorce w `StructuralEngine.kt:356–365`. Testy JVM: `PseudonymEngineTest.kt:265–277`, `:305+` (S10/S10b).

**OK** — OutputGuard `TELEFON_PELNY` (RED)  
`OutputGuard.kt:66` — łapie formaty z separatorami, kropką, 0048, nawiasami. UI blokuje copy/send/bibliotekę przy RED.

**PROBLEM [RODO]** — znany edge case OCR  
`OcrDegradationTest.kt:287–290` `@Ignore("BUG-TEL-PREFIX")`:  
`48 60l 234 567` — degradacja OCR, **nie trafia do tokenMap**. Test oczekuje wykrycia po normalizacji `l→1`.

**PROBLEM [RODO]** — architektura Guard vs silnik  
OutputGuard **flaguje**, ale **nie podmienia** tekstu. `pseudonymizedText` może zawierać goły numer gdy:
- silnik nie zamaskował (edge OCR),
- Guard nie złapał formatu,
- użytkownik obejdzie UI (schowek — pkt 6).

**Werdykt pkt 10:**  
Numery **standardowe** → pseudonimizowane tokenem. **Edge case OCR + schowek** → potencjalny wyciek PII mimo „pseudonimizacji”. To **nie** jest wyłącznie flagowanie — RED Guard blokuje eksport w UI share, ale **nie** chroni schowka ani logów DEBUG.

---

## 11. BUGI LOGICZNE W PIPELINE

### 11A. NIP S5 — niespójne walidowanie (`StructuralEngine.kt`)

**OK** — projektowe i udokumentowane  

Ten sam format NIP (np. `521-334-15-33`):
- **Z kontekstem** `NIP:` → wzorzec kontekstowy (`StructuralEngine.kt:212–214`) — **poza** `NIP_PATTERN_STRINGS` → **bez S5** → maskuje nawet przy błędnej sumie OCR  
- **Bez kontekstu** → wzorzec strukturalny (`:347`) — **w** `NIP_PATTERN_STRINGS` → **S5 obowiązkowe**

Komentarz nienaruszalny: `StructuralEngine.kt:533–546`.

**Bezpieczeństwo RODO:** bypass S5 przy keyword **zwiększa** maskowanie (mniej FN), nie zmniejsza.  
**Ryzyko:** goły NIP z błędną sumą OCR bez słowa „NIP” → S5 odrzuci → FN (wyciek PII w output), nie over-masking.

---

### 11B. BUG-28 — NAME_FORWARD/BACKWARD regex cache (`NameEngine.kt`)

**OK** — ścieżki produkcyjne (normalny start)  

`LookupTables.initialize()` **synchroniczne**, wywołane przed pierwszym `pseudonymize()`:
- `MainActivity.onCreate():65` (Dispatchers.IO, przed UI)
- `ShareTargetActivity.onCreate():98` (przed `setContent`)
- `ClipboardCheckActivity.onCreate():88`

`PseudonymEngine.kt:114–124` — guard: w DEBUG `error()` gdy nie zainicjowane; w release log + tryb degradowany (200 imion fallback).

**OK** — fix S1/S2 vs stary `lazy val`  
`NameEngine.kt:390–399` — backing var `_nameForwardRegex` + `resetRegexCache()`, nie `by lazy`.

**PROBLEM [BUG]** — `resetRegexCache()` **nie** wywoływane po `LookupTables.initialize()` w produkcji  
Tylko w teardown testów (`PseudonymEngineTest.kt:34`, `NameEngineCapsAsciiTest.kt:23`).

Teoretyczny scenariusz release: jeśli `NAME_FORWARD_REGEX` zostanie skompilowany **przed** init (race lub błąd assets → `_initialized=false`), regex z **200 fallback names** zostanie w cache na cały proces.

**PROBLEM [BUG]** — `BenchmarkService.kt:83`  
Wywołuje `PseudonymEngine.pseudonymize()` **bez** `LookupTables.initialize()` — tryb degradowany gwarantowany na benchmarku instrumented.

**Rekomendacja:** wywołać `resetRegexCache()` na końcu `LookupTables.initialize()` oraz w `BenchmarkService.onCreate`.

---

### 11C. ADDRESS_PATTERNS a TOKEN_RE guard (`PseudonymEngine.kt`)

**OK** — guard obecny  

```kotlin
for ((tokenType, pattern) in ADDRESS_PATTERNS) {
    pattern.findAll(text).toList().asReversed().forEach { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@forEach
        text = text.replaceRange(match.range, assignToken(...))
    }
}
```
(`PseudonymEngine.kt:295–299`)

Każda iteracja ADDRESS sprawdza `TOKEN_RE` przed `replaceRange`. Wzorce STRUCTURAL używają analogicznego guarda (`:211`, `:268`, `:280`).

Brak brakującego guarda — podwójne maskowanie w sekcji ADDRESS **nie wykryto**.

---

## Tabela zbiorcza

| # | Temat | Werdykt | Priorytet |
|---|---|---|---|
| 1 | Logowanie PII | **PROBLEM** (DEBUG leaks) | P1 |
| 2 | Pliki tymczasowe | **PROBLEM** (cache JPEG) | P2 |
| 3 | SessionStore crypto | **OK** (+ alpha crypto lib) | P2 |
| 4 | Offline / sieć | **OK** (+ GMS NIEZNANE) | — |
| 5 | Eksport plików | **PROBLEM** (.lynxdict, depseudo) | P1 |
| 6 | Clipboard | **PROBLEM** (ClipboardCheck) | P1 |
| 7 | Depseudonymizacja | **PROBLEM** (export bez warn) | P1 |
| 8 | Image redaction | **OK** (+ cache cleanup) | P2 |
| 9 | Uprawnienia | **OK** | — |
| 10 | TELEFON S10 | **PROBLEM** (edge OCR + Guard≠replace) | P1 |
| 11A | NIP S5 | **OK** (projektowe) | — |
| 11B | LookupTables init | **PROBLEM** (brak resetRegexCache) | P2 |
| 11C | ADDRESS guard | **OK** | — |

---

## Rekomendowane fixy (kolejność)

### P1 — RODO / wyciek PII

1. **S10 / BUG-TEL-PREFIX** — wzorzec OCR-tolerant dla `48 60l 234 567` → tokenMap; test `@Ignore` → green.
2. **ShareTargetActivity:568** — usunąć log `rawText.take(300)`; tylko metadane (długość, conf).
3. **ShareTargetActivity:405,409** — logować typ + długość, nie `'$word'`.
4. **ClipboardCheckActivity** — „czysto” gdy `guardHits.isEmpty() && flags.isEmpty() && riskScore == GREEN`; przy replace sprawdzić `redHits` / brak surowego PII w tekście.
5. **DepseudonymizationScreen** — AlertDialog przed „Pobierz plik” (BUG-EXPORT-DEPSEUDO).
6. **OutputGuard vs tekst** — rozważyć auto-maskowanie RED hits w `pseudonymizedText` (nie tylko flaga).

### P2 — hardening

7. **ImageRedactionPipeline** — kasowanie pliku cache po share lub TTL w `onDestroy`.
8. **`resetRegexCache()`** na końcu `LookupTables.initialize()`.
9. **`security-crypto`** — upgrade do stabilnej `1.0.0` (AUD-M06).
10. **`isOcrQualityAcceptable()`** — hard reject w pipeline OCR (kod w `OcrQuality.kt`, obecnie tylko soft warning w `assessQuality`).

### P3 — dokumentacja / UX

11. Dialog ostrzegawczy przed eksportem `.lynxdict` (PII plaintext).
12. Usunąć lub zabezpieczyć `LynxMaskLogger.kt` (dead code z pełnym PII logging).

---

## Pliki kluczowe (indeks)

| Plik | Rola w audycie |
|---|---|
| `DebugLogBuffer.kt` | Bufor DEBUG, polityka RODO v1.1 |
| `ShareTargetActivity.kt` | OCR, log leak :568, FLAG_SECURE |
| `SessionStore.kt` | SQLCipher + AES-GCM |
| `UserDictionary.kt` | EncryptedFile + export plaintext .lynxdict |
| `PseudonymResultPanel.kt` | canSend / RED blocking |
| `ClipboardCheckActivity.kt` | Schowek — fałszywe „czysto” |
| `DepseudonymizationScreen.kt` | Export PII bez ostrzeżenia |
| `ImageRedactionPipeline.kt` | Cache JPEG |
| `StructuralEngine.kt` | NIP S5 bypass dokumentacja |
| `NameEngine.kt` | Regex cache / BUG-28 |
| `PseudonymEngine.kt` | LookupTables guard, ADDRESS guard |
| `AndroidManifest.xml` | Brak INTERNET, backup off |
| `app/build.gradle.kts` | ML Kit, security-crypto alpha |

---

## Ograniczenia audytu

- Przegląd **statyczny** — bez uruchomienia APK na urządzeniu.
- GMS Document Scanner — zachowanie sieciowe przy pierwszym uruchomieniu: **NIEZNANE**.
- Cleanup cache po share intent: **NIEZNANE** (brak callbacku w kodzie).
- Release build z `isMinifyEnabled = false` — ProGuard nie redukuje powierzchni; poza zakresem tego audytu.

---

---

## Dodatek: ciche bugi — niewidoczne w teście ręcznym i benchmarku

*Sekcja dopisana na prośbę właściciela — bugi które przy „normalnym” układzie nie krzyczą, a przy pechowym układzie produkują wyciek lub fałszywe poczucie bezpieczeństwa.*

### Dlaczego benchmark i ręczne testy tego nie łapią

| Co testujesz | Czego **nie** mierzy |
|---|---|
| **Benchmark instrumented** | Tylko `tokenMap` — **ignoruje `guardHits`** (`BenchmarkInstrumentedTest.kt:148–154`, `:266–273`) |
| **Test ręczny share flow** | Typowe dokumenty, świadome klikanie — nie schowek, nie edge OCR |
| **266 testów JVM** | Tekst syntetyczny, `LookupTables.initializeForTesting()` zawsze przed testem |
| **Recall w raporcie** | Sukces = encja w tokenMap; Guard RED **nie liczy się** jako wykrycie |

**Konsekwencja:** masz dwa ślepe zaułki:
1. **Benchmark czerwony, produkt bezpieczny** — telefon tylko w Guard RED → miss w raporcie, ale UI zablokuje wysyłkę.
2. **Benchmark zielony / UI „OK”, wyciek** — encja poza tokenMap **i** poza Guard → użytkownik nie widzi nic podejrzanego.

Drugi przypadek to ten, którego się boisz.

---

### Tier S — ciche wycieki (produkt wygląda OK)

#### S1. Schowek mówi „czysto” mimo PII w tekście

**Plik:** `ClipboardCheckActivity.kt:135–136`

Warunek bezpieczeństwa: `flags.isEmpty() && riskScore == GREEN`.

**Nie sprawdza:** czy w `pseudonymizedText` nadal są gołe cyfry / imiona (Guard mógł nie złapać formatu).

**Scenariusz pechowy:** użytkownik kopiuje fragment umowy z telefonem w formacie OCR `48 60l 234 567` (test `@Ignore` w `OcrDegradationTest.kt:287`). Toast: „Schowek wygląda bezpiecznie”. **Zero UI ostrzeżenia.**

**Test ręczny:** nie testujesz kafelka schowka na zdegradowanym OCR.  
**Benchmark:** nie uruchamia ClipboardCheck w ogóle.

---

#### S2. „Bezpieczna wersja” schowka nadal zawiera PII

**Plik:** `ClipboardCheckActivity.kt:172–175`

„Zastąp schowek” wkleja `pseudonymizedText`. OutputGuard **flaguje**, ale **nie podmienia** tekstu (`PseudonymEngine.kt:306` — tylko `guardHits`).

**Scenariusz:** dialog PII się pojawi (YELLOW/RED), użytkownik klika „zastąp” — w schowku **nadal** jest numer, jeśli silnik go nie zamaskował.

**Wygląda jak fix, jest wyciekiem.**

---

#### S3. TELEFON / NUMER — double miss (silnik + Guard)

**Warstwy:** `StructuralEngine.kt:356–365` + `OutputGuard.kt:66`

Większość telefonów: token `NUMER_*` **lub** Guard RED. Edge case OCR (bez `+`, `l` zamiast `1`, brak separatorów) — oba mogą chybić.

**Benchmark:** miss → wpis w raporcie (wygląda jak „silnik słaby”).  
**Produkcja:** jeśli użytkownik nie idzie przez share (schowek, Podgląd tekstu → ręczny forward) — **cichy wyciek**.

Test `@Ignore` dokumentuje ten przypadek — **nie blokuje release metryką**.

---

#### S4. S5 odrzuca goły NIP/PESEL — Guard też nie łapie

**Plik:** `StructuralEngine.kt:533–546`, `PseudonymEngine.kt:228–232`

Goły NIP 10 cyfr z **jedną** cyfrą przekręconą przez OCR → S5 fail → **brak tokena**. Bez słowa „NIP:” w tekście wzorzec kontekstowy nie pomoże. OutputGuard nie ma wzorca „goły NIP”.

**Benchmark:** `BUG_SILNIKA` lub miss — widzisz w raporcie.  
**Ręczny test:** jeśli OCR dał poprawny NIP → nie reprodukujesz.  
**Pech:** słabe zdjęcie faktury, NIP w tabeli bez etykiety → NIP w „zamaskowanym” tekście, brak RED.

---

### Tier A — zatrute cache / kolejność (raz na proces, potem na zawsze)

#### A1. Regex imion skompilowany z 200 fallback — na cały proces

**Pliki:** `NameEngine.kt:394–399`, `PseudonymEngine.kt:114–124`

Pierwsze `NAME_FORWARD_REGEX` buduje się z `buildNamePattern()` — jeśli wtedy `LookupTables.initialized == false`, w regex wchodzi **200 imion** zamiast ~1874. Backing var **cache’uje na zawsze** do `resetRegexCache()`.

**Produkcja:** `resetRegexCache()` **tylko w testach**, nigdy po `LookupTables.initialize()`.

**Scenariusze pechowe:**
- `BenchmarkService.kt:83` — pseudonimizuje bez init (narzędzie dev, ale ten sam proces JVM co aplikacja?)
- uszkodzone / brakujące assets → `_initialized = false` (`LookupTables.kt:53`) → release loguje `DEGRADED MODE` **tylko w Logcat** — użytkownik widzi normalny UI
- teoretyczny race: kod wywołany przed końcem init (ShareTarget OK sync; MainActivity czeka na init przed `setContent` — **OK**)

**Objaw:** rzadkie nazwiska z słownika nie maskowane ** przez całą sesję aplikacji**. Benchmark po `LookupTables.initialize(context)` (:60) — **nie reprodukuje**, jeśli sam benchmark startuje cleanly.

---

#### A2. GuardAllowlist / UserDictionary — trwałe „wyłączenie ochrony”

Użytkownik klika „nie maskuj” na fałszywym YELLOW → wpis w allowlist **na zawsze** (dla tego fragmentu). Kolejne dokumenty z tym samym wzorcem — **cichy wyciek**, zero regresji w benchmarku (benchmark czyści słownik `:79`, allowlist **nie**).

---

### Tier B — benchmark kłamie w drugą stronę (fałszywy alarm, nie wyciek)

#### B1. Guard RED ≠ wykrycie w benchmarku

Telefon w tekście, Guard RED, brak tokena → **recall −1**, krytyczny miss w raporcie. Ręcznie: UI **blokuje** wysyłkę. Optymalizujesz silnik pod coś, co produkt już blokuje — **szum**, nie wyciek.

#### B2. BRAK_W_OCR vs BUG_SILNIKA

Benchmark rozróżnia (`:524`, `:546`), ale w summary i tak jedna liczba recall. Przy lvl 2 mylisz „sufit OCR” z „bug silnika” — **decyzje architektoniczne na złych danych** (motyw Benchmark v2 z briefu właściciela).

---

### Tier C — infrastruktura bez twardej bramki

#### C1. `isOcrQualityAcceptable()` istnieje, nie odrzuca dokumentu

**Plik:** `OcrQuality.kt` — próg 0.60; pipeline używa tylko `assessQuality()` → **banner ostrzegawczy**, dokument idzie dalej.

**Pech:** OCR dał 40% tekstu poprawnie → część PII zamaskowana, reszta nie → GREEN/YELLOW, użytkownik wysyła „prawie bezpieczne”.

**Benchmark lvl 0/1:** wygląda dobrze. **Realne zdjęcie z ręki:** cichy partial leak.

#### C2. `SessionStore.save()` zwraca `false` — częściowy fail

Share flow pokazuje Toast (`ShareTargetActivity.kt:424–427`). Ścieżka schowka `ClipboardCheckActivity` — **sprawdzić** czy ten sam Toast przy fail zapisu. Sesja w UI „zapisana w głowie użytkownika”, tokenMap **nie** w bazie → depseudo niemożliwe (utrata funkcji, nie wyciek) — inny rodzaj cichej awarii.

---

### Macierz: co łapie który kanał testowy

| Scenariusz | Test ręczny | Benchmark | JVM test | Schowek / kafelek |
|---|:---:|:---:|:---:|:---:|
| Regex cache 200 imion | ❌ | ❌* | ❌ (zawsze init) | ❌ |
| TELEFON double miss OCR | ❌ | ⚠️ miss w raporcie | ❌ (`@Ignore`) | ❌ fałszywe OK |
| Guard RED, brak token | ⚠️ UI blokuje | ❌ liczy miss | częściowo | ⚠️ |
| S5 FN goły NIP | ❌ | ⚠️ | częściowo | ❌ |
| Allowlist permanent | ❌ | ❌ | ❌ | ❌ |
| Słabe OCR partial | ⚠️ banner | lvl2 szum | ❌ | ❌ |
| Schowek „bezpieczna wersja” z PII | ❌ | ❌ | ❌ | **wyciek** |

\* benchmark robi init LookupTables, więc nie widzi A1 w typowym runie

---

### Co zrobić, żeby ciche bugi **wyjść na jaw** (propozycje)

1. **Test integracyjny „double miss”** — jeden plik tekstowy z `@Ignore` przypadkami (telefon OCR, goły NIP S5 fail); assert: `tokenMap` **lub** `guardHits RED` **lub** hard reject OCR. Fail = wyciek.

2. **Benchmark v2 sekcja SAFETY** — obok recall tokenów: `% encji krytycznych covered by (token OR guard RED)` oraz `% dokumentów z RED guard bez tokena`.

3. **`resetRegexCache()` na końcu `LookupTables.initialize()`** — jedna linia, zamyka A1.

4. **ClipboardCheck** — warunek czystości: `guardHits.isEmpty() && !containsUnmaskedCriticalPatterns(pseudonymizedText)`; replace schowka tylko gdy ten sam test pass.

5. **Metryka produktowa** — przy release smoke: 5 celowo zepsutych snippetów (z `OcrDegradationTest` LVL3) przez **schowek + share**, nie tylko benchmark.

6. **Nie polegać na recall jako jedynym KPI** — patrz `BRIEF_Wlasciciel_Benchmark_v2.md`.

---

### Jedno zdanie

> Najgroźniejsze są bugi **Tier S**: output wygląda jak po pseudonimizacji, metryki milczą lub mylą, a PII wychodzi przez **schowek** albo **format OCR którego generator benchmarku nie produkuje**.

---

*Koniec raportu. Wygenerowano przez Cursor na podstawie `BRIEF_Cursor_Security_RODO.md`.*
