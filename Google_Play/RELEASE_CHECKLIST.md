# LynxMask — Lista zadań przed release Google Play

## TWOJE ZADANIA (wymagane zanim wgram .aab)

### 1. Keystore — klucz podpisujący apkę
- [ ] Powiedz mi że jesteś gotowy — wygeneruję keystore razem z Tobą
- [ ] Zapisz plik `.jks` w bezpiecznym miejscu (np. pendrive + chmura prywatna)
- [ ] Zapamiętaj / zapisz hasło keystora i hasło klucza (bez nich nie zaktualizujesz apki NIGDY)

### 2. Konto Google Play Console
- [ ] Wejdź na play.google.com/console
- [ ] Zapłać $25 (jednorazowo) — karta lub PayPal
- [ ] Utwórz konto dewelopera (nazwa firmy lub imię — będzie widoczna na sklepie)

### 3. Firma testerów
- [ ] Zamów pakiet 12 lub 16 testerów (Closed Testing wymaga min. 12)
- [ ] Zbierz ich e-maile kont Google (potrzebne do zaproszenia w Play Console)

---

## NASZE ZADANIA (po tym jak masz keystore i konto)

- [ ] Zbudować podpisany `.aab` (`gradlew bundleRelease`)
- [ ] Wgrać `.aab` do Play Console → Closed Testing track
- [ ] Wgrać 6 screenshotów z folderu `Google_Play/`
- [ ] Napisać opis apki na sklep (PL + opcjonalnie EN)
- [ ] Uzupełnić formularz prywatności w Play Console (link do polityki)
- [ ] Zaprosić testerów przez e-mail lub link opt-in
- [ ] Odczekać 14 dni z 12+ aktywnymi testerami
- [ ] Złożyć wniosek o przejście do produkcji

---

## Kolejność

**Ty najpierw:** Keystore → Konto Play Console → Testerzy  
**Potem my:** Build → Wgranie → Opis → Zaproszenia → Czekanie 14 dni → Produkcja

---

## Screenshoty gotowe

Folder `Google_Play/` zawiera 6 plików w kolejności do wgrania:
- 01_HUB.jpg
- 02_PODGLĄD.jpg
- 03_PODGLĄD2.jpg
- 04_BIBLIOTEKA.jpg
- 05_ODPOWIEDŹ_AI.jpg
- 06_ZABEZPIECZENIA.jpg
