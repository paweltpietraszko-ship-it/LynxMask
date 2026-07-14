package com.lynxmask.app

// NameEngine.kt — Słowniki, białe listy, detekcja nazw własnych
// Wersja: 1.12
//
// Zmiany v1.12 (S3 — inicjały przy nazwiskach):
//   - INITIALS_REGEX: "K. Kowalski" → maskuje całość jako OSOBA
//     Warunek: nazwisko MUSI być w surnamesForms (zerowy FP przy nieznanym nazwisku)
//     Uruchamiany przed "Samo nazwisko z surnamesForms" — zapobiega podwójnemu tokenizowaniu
//
// Zmiany v1.11 (OSOBA_DENYLIST — redukcja FP z logów benchmarku):
//   - OSOBA_DENYLIST: deny-lista przed każdym assignToken(..., TOKEN_OSOBA).
//     Łapie OCR-garbled warianty ("wydzialu"/"wydziatu") i słowa pospolite
//     które WHITE_LIST_COMMON_WORDS pomija gdy brakuje polskich znaków po OCR.
//   - LENGTH-FIX v1.11: próg <4 znaków rozszerzony na HONORIFIC (surnamePart)
//     i TITLE_PATTERN (namepart) — analogicznie do istniejącego w NAME_FORWARD/BACKWARD.
//
// Zmiany v1.10 (Potok 3c — redukcja false positives):
//   1. WHITE_LIST_FP: rozszerzona WHITE_LIST_COMMON_WORDS o słowa pospolite
//      i skróty które benchmark B+C fałszywie wykrywał jako OSOBA:
//      • "zamieszkania" i odmiany — słowo pospolite w nagłówkach adresowych
//        ("Adres zamieszkania:", "Miejsce Zamieszkania:") z wielką literą
//        po nagłówku trafiało do NAME_FORWARD/BACKWARD jako imię
//      • "wydziału" i odmiany — nazwa instytucji bez prefiksu "wydział"
//        w WHITE_LIST_INSTITUTIONS_PREFIX
//      • "sp", "sł", "poe", "pl" — 2-3 znakowe skróty/artefakty OCR
//   2. LENGTH-FIX: dodany warunek surname.length < 4 w NAME_FORWARD_REGEX
//      i NAME_BACKWARD_REGEX. Wzorzec nazwiska akceptuje ≥2 znaki; 2-3 znakowe
//      fragmenty ("Sp", "Ko") jako fałszywe nazwiska blokowane przez warunek.
//      Nie dotyczy HONORIFIC i TITLE_PATTERN — tam kontekst (honorifik/tytuł)
//      jest wystarczającym filtrem.
//      UWAGA: "Jan", "Ewa" (3 znaki) jako imię są w grupie 1 (buildNamePattern),
//      nie w grupie 2 (surname) — warunek ich nie dotyka.
//
// Zmiany v1.9 (Potok 3b — BUG-FLAGS):
//   Cztery niezależne fałszywe alarmy w detectAlgorithmicFlags (Warstwa 5):
//
//   1. FLAGS-FIX-PAIR: para słów (np. "Zielonej Górze") sprawdzana jest teraz
//      razem przeciwko białej liście przez isOnWhiteList("$word $nextWord").
//      Poprzednio sprawdzane było tylko nextWord osobno — "zielonej górze"
//      istnieje w WHITE_LIST_CITIES ale "zielonej" samodzielnie nie.
//
//   2. FLAGS-FIX-CITY: dodano pojedyncze formy fleksyjne "górze", "górę", "górą"
//      do WHITE_LIST_CITIES. Poprzednio wpisy były tylko wielosłowowe;
//      "Górze" wykrywane jako "Możliwe nazwisko" gdy poprzedzało czasownik.
//
//   3. FLAGS-FIX-KOMISJA: dodano prefiks "komisj" do WHITE_LIST_INSTITUTIONS_PREFIX.
//      Pokrywa wszystkie formy fleksyjne "komisja/komisji/komisją/..." —
//      "Komisji Mieszkaniowej" przestaje być flagowane jako nazwa własna.
//
//   4. FLAGS-FIX-ADJ: VERB_ENDINGS blok wywołuje teraz isAdjective(word).
//      isAdjective() istniała i działała poprawnie w Warstwie 3 — ale Warstwa 5
//      jej nie używała. "Mieszkaniowej" (przymiotnik) flagowane jako "Możliwe
//      nazwisko" gdy poprzedzało czasownik.
//      DODATKOWO: isAdjective(word) i isOnWhiteList("$word $nextWord") dodane
//      do warunków pary — "Społecznej Komisji" przestaje być flagowane.
//
// Zmiany v1.8 (Potok 3b):
//   - BUG-27-FIX: applyContextualBlacklist — usunięto @Suppress("UNUSED_PARAMETER").
//     profileType zachowany z powodów API (onboarding przekazuje wartość).
//     Dodano TODO: Potok-PROFIL z opisem planowanego użycia.
//
// Zmiany v1.2:
//   - BUG-1: WHITE_LIST_COMMON_WORDS rozszerzona o odmiany "adres/adresem/adresów"
//            NAME_FORWARD_REGEX, NAME_BACKWARD_REGEX, titlePattern sprawdzają
//            teraz OBIE grupy (imię i nazwisko) na białej liście
//   - BUG-2: TITLE_ADJECTIVE_ENDINGS nie blokuje odmienionego nazwiska ze słownika
//            ("Kowalskiego" kończy się na -iego ale jest w surnamesForms → nie blokuj)
//
// Zmiany v1.3 (sesja 6 — komentarz nie był aktualizowany w pliku):
//   - BUG-2: isAdjective() sprawdza surnamesForms przed blokowaniem
//   - FIRMA-FIX: forma prawna silniejszym sygnałem niż WHITE_LIST_INSTITUTIONS
//
// Zmiany v1.4 (sesja 7):
//   - P6-FIX: WHITE_LIST_LEGAL_FORMS rozszerzona o warianty OCR "Sp.Z o.o."
//             Dodano LEGAL_FORM_CHECK_REGEX jako drugą linię obrony —
//             hasLegalForm teraz używa OBU metod (regex OR contains)
//   - P7-FIX: WHITE_LIST_CITIES — największe polskie miasta nie są już
//             wykrywane jako OSOBA (Kraków, Gdańsk itd.)
//
// Zmiany v1.5 (sesja 7):
//   - STRUCT-FIX: WHITE_LIST_INSTITUTIONS podzielona na PREFIX (startsWith
//                 bezpieczny) i EXACT (tylko exact match). Słowa niejednoznaczne
//                 jak "centrum","zakład" nie blokują już przez prefix.
//   - REGEX-FIX: FIRMA_LEGAL_REGEX — \s → [^\S\n] w treści nazwy firmy;
//                poprzednia wersja mogła zszywać linie z różnych akapitów
//   - CITIES-FIX: dodano brakujący dopełniacz "zielonej góry"
//
// Zmiany v1.6 (sesja 7):
//   - PERF-FIX: titlePattern przeniesiony z applyContextualBlacklist() do
//               module-level lazy val TITLE_PATTERN_REGEX — eliminuje
//               kompilację dużej alternacji (~60 wpisów) przy każdym wywołaniu
//
// Zmiany v1.7 (sesja 10):
//   - TODO-2-FIX: WHITE_LIST_COMMON_WORDS rozszerzona o angielskie słowa z faktur
//     i umów zagranicznych. Problem: "ADDRESS" → OSOBA, "Bill" → OSOBA, "NOT" → FIRMA,
//     "Poland" → FIRMA na dokumentach anglojęzycznych. Dodano najczęstsze angielskie
//     słowa które mogą być wykryte jako polskie imiona/nazwy przez stem-matching.

// ============================================================
// Warstwa 3a — Słownik imion PESEL (case-insensitive)
// Pełna lista 1460 imion ładowana z assets
// Tu: najczęstsze 200 jako fallback
// ============================================================
private val POLISH_FIRST_NAMES: Set<String> = setOf(
    // Top 50 męskich
    "jan", "piotr", "krzysztof", "andrzej", "tomasz", "stanisław", "marcin",
    "michał", "marek", "paweł", "jakub", "adam", "łukasz", "grzegorz", "mateusz",
    "robert", "rafał", "szymon", "dariusz", "mariusz", "kamil", "przemysław",
    "bartłomiej", "filip", "maciej", "wojciech", "artur", "damian", "sebastian",
    "konrad", "karol", "patryk", "dominik", "bartosz", "dawid", "jacek",
    "zbigniew", "tadeusz", "edward", "henryk", "ryszard", "mirosław", "ireneusz",
    "waldemar", "wiesław", "zygmunt", "jerzy", "leszek", "józef", "władysław",
    // Top 50 żeńskich
    "anna", "maria", "katarzyna", "małgorzata", "agnieszka", "barbara", "ewa",
    "krystyna", "teresa", "elżbieta", "zofia", "helena", "danuta", "halina",
    "irena", "jolanta", "monika", "beata", "dorota", "alicja", "joanna",
    "natalia", "weronika", "karolina", "marta", "magda", "magdalena", "julia",
    "aleksandra", "paulina", "martyna", "patrycja", "ewelina", "sylwia",
    "justyna", "renata", "iwona", "grażyna", "bożena", "genowefa", "janina",
    "stanisława", "władysława", "cecylia", "leokadia", "marianna", "wanda",
    "jadwiga", "bronisława", "antonina",
    // Imiona obce popularne w Polsce
    "robert", "diana", "laura", "sara", "oliwia", "maja", "zuzanna", "hanna",
    "tomasz", "artur", "dominika", "klaudia", "edyta", "gabriela", "izabela",
    "natalia", "nikola", "wiktoria", "aleksander", "nikodem", "leon", "ignacy",
    "franciszek", "stanisław", "tymoteusz", "miłosz", "kacper", "oskar",
    // Skrócone formy które mogą wystąpić w dokumentach
    "hans", "peter", "markus", "thomas", "anna", "maria", "jan", "tomasz"
).map { it.lowercase() }.toHashSet()

// ============================================================
// Warstwa 3b — Lista funkcji i stanowisk (kontekst dla nazwisk)
// ============================================================
private val FUNCTION_TITLES: Set<String> = setOf(
    // Zarządcze
    "dyrektor", "wicedyrektor", "kierownik", "prezes", "wiceprezes",
    "przewodniczący", "wiceprzewodniczący", "sekretarz", "skarbnik",
    "pełnomocnik", "prokurent", "likwidator", "zarząd", "członek",
    // Prawnicze
    "sędzia", "prokurator", "adwokat", "radca", "notariusz", "komornik",
    "mecenas", "mediator", "arbiter", "kurator", "syndyk", "rzecznik",
    "asesor", "aplikant", "obrońca",
    // Medyczne
    "ordynator", "lekarz", "doktor", "chirurg", "internista", "pediatra",
    "kardiolog", "neurolog", "psychiatra", "onkolog", "pielęgniarka",
    "położna", "ratownik", "farmaceuta", "diagnosta", "rezydent", "stażysta",
    // Akademickie
    "rektor", "prorektor", "dziekan", "prodziekan", "profesor", "docent",
    "adiunkt", "asystent", "wykładowca", "promotor",
    // Samorządowe
    "wójt", "burmistrz", "prezydent", "starosta", "marszałek", "wojewoda",
    "naczelnik", "inspektor", "radny", "sołtys", "referent", "urzędnik",
    // Służby mundurowe
    "komendant", "oficer", "aspirant", "posterunkowy", "sierżant",
    "kapitan", "major", "podpułkownik", "pułkownik", "generał", "strażak",
    // Kościelne
    "ksiądz", "proboszcz", "wikary", "biskup", "arcybiskup", "kardynał",
    "pastor", "rabin", "imam",
    // Zagraniczne (najczęstsze w polskich dokumentach)
    "geschäftsführer", "manager", "director", "president", "ceo",
    // Skróty
    "mgr", "dr", "prof", "inż", "lic", "mec", "adw", "not", "lek", "piel"
).map { it.lowercase() }.toHashSet()

// ============================================================
// Warstwa 4 — Biała lista (zostaw zawsze)
// ============================================================
// STRUCT-FIX v1.5: podział na dwie listy
// PREFIX — startsWith bezpieczny: te słowa jednoznacznie identyfikują instytucję publiczną
// EXACT  — słowa niejednoznaczne (np. "centrum" może być firmą): tylko exact match
private val WHITE_LIST_INSTITUTIONS_PREFIX: Set<String> = setOf(
    "sąd", "trybunał", "prokuratura", "urząd", "ministerstwo", "minister",
    "kancelaria", "starostwo", "gmina", "powiat", "województwo", "sejm",
    "senat", "komenda", "straż", "policja", "szpital", "przychodnia",
    "politechnika", "uniwersytet",
    // FLAGS-FIX-KOMISJA v1.9: "komisj" pokrywa komisja/komisji/komisją/komisje/
    // komisjach/komisjom — wszystkie formy fleksyjne przez prefix startsWith.
    "komisj",
    // Stemy przymiotnikowe — pokrywają formy m/ż/n i odmianę przez przypadki.
    // "miejski/miejska/miejskie/miejskiego/miejskiej" → stem "miejsk".
    // Zamiast pełnych form, które pomijają formy żeńskie i odmianę.
    "narodow", "państwow", "publiczn", "miejsk",
    "gminn", "powiatow", "wojewódz", "centraln", "główn", "generaln",
    "okręgow", "rejonow", "samorządow", "skarbow",
    // Jednostki organizacyjne — formy fleksyjne przez prefix:
    // "wydziału/wydziałem/wydziałów" → "wydział"
    // "zarządu/zarządzie/zarządem" → "zarząd"
    // "spółki/spółką/spółkę" → "spółk"
    // "oddziału/oddziałem/oddziałów" → "oddział"
    "wydział", "zarząd", "spółk", "oddział"
).map { it.lowercase() }.toHashSet()

// Słowa niejednoznaczne — "centrum" może być prywatną firmą (Centrum Doradztwa Pawlak)
// Dozwolone WYŁĄCZNIE jako samodzielny wyraz, nie jako prefix nazwy firmy
private val WHITE_LIST_INSTITUTIONS_EXACT: Set<String> = setOf(
    "zakład", "instytut", "akademia", "szkoła", "centrum", "kolegium",
    "laboratorium", "diagnostyka", "spółdzielnia"
).map { it.lowercase() }.toHashSet()

private val WHITE_LIST_CONTRACTUAL: Set<String> = setOf(
    "wykonawca", "zamawiający", "pełnomocnik", "zleceniodawca",
    "zleceniobiorca", "cedent", "cesjonariusz", "nabywca", "zbywca",
    "sprzedawca", "kupujący", "wynajmujący", "najemca", "dzierżawca",
    "pożyczkodawca", "pożyczkobiorca", "pracodawca", "pracownik",
    "ubezpieczony", "ubezpieczyciel", "wierzyciel", "dłużnik",
    "poręczyciel", "gwarant", "beneficjent", "strona", "kontrahent",
    "powód", "pozwany", "wnioskodawca", "oskarżony", "świadek", "biegły",
    "sprzedający", "kupujący", "inwestor", "zlecający", "odbiorca",
    "likwidator", "ubezpieczający", "asesor",
    // Zobowiązaniowe — false positives z poprzedniej sesji
    "zobowiązania", "wierzytelność", "wierzytelności", "zobowiązanie",
    "należność", "należności", "roszczenie", "roszczenia", "odszkodowanie",
    "wynagrodzenie", "świadczenie", "świadczenia", "zabezpieczenie"
).map { it.lowercase() }.toHashSet()

private val WHITE_LIST_DOCUMENTS: Set<String> = setOf(
    "umowa", "aneks", "załącznik", "protokół", "faktura", "nota",
    "rachunek", "oferta", "zamówienie", "zlecenie", "wniosek", "podanie",
    "pismo", "decyzja", "postanowienie", "wyrok", "nakaz", "zaświadczenie",
    "świadectwo", "certyfikat", "polisa", "regulamin", "statut", "ustawa",
    "kodeks", "rozporządzenie", "dyrektywa", "uchwała", "prawo", "dekret"
).map { it.lowercase() }.toHashSet()

private val WHITE_LIST_LEGAL_FORMS: Set<String> = setOf(
    // Polskie — wariant kanoniczny
    "sp. z o.o.", "spółka z ograniczoną odpowiedzialnością", "s.a.", "spółka akcyjna",
    "sp.j.", "sp.k.", "s.k.a.", "p.s.a.",
    // P6-FIX: warianty OCR — bez spacji po kropce, wielka litera Z, brakujące spacje
    "sp.z o.o.", "sp.zo.o.", "sp.z.o.o.", "sp. z o. o.", "sp.z.o.o",
    // Zagraniczne
    "gmbh", "ag", "kg", "ohg", "ltd", "llp", "plc", "llc", "inc", "corp",
    "s.r.l.", "s.p.a.", "s.a.s.", "sarl", "eurl", "b.v.", "n.v.", "a/s", "aps", "ab"
).map { it.lowercase() }.toHashSet()

// P6-FIX: regex jako druga linia obrony — obsługuje dowolne kombinacje
// spacji, wielkich liter i brakujących kropek generowanych przez OCR
// BUG-FIRMA-PRZECINEK-FIX (01.07): [.,]? zamiast \.? — toleruje też przecinek (nie tylko
// brak kropki) jako OCR-zamiennik kropki w formie prawnej.
private val LEGAL_FORM_CHECK_REGEX = Regex(
    """(?i)sp[.,]?\s*z[.,]?\s*o[.,]?\s*o[.,]|s[.,]?\s*a[.,]|sp[.,]?\s*j[.,]|sp[.,]?\s*k[.,]|s[.,]k[.,]a[.,]|p[.,]s[.,]a[.,]"""
)

private val WHITE_LIST_COMMON_WORDS: Set<String> = setOf(
    // Przyimki i spójniki które stem matching może pomylić z imionami
    "bez", "przez", "przed", "między", "według", "wobec", "około", "wśród",
    "poza", "ponad", "pod", "nad", "przy", "dla", "jako", "oraz", "albo",
    "lecz", "jednak", "czyli", "więc", "zatem", "gdyż", "chociaż",
    // Słowa kontekstu prawnego/adresowego
    "kodu", "pocztowego", "numer", "ulicy", "lokalu", "budynku",
    "miejscowości", "gminy", "powiatu", "dzielnicy",
    // BUG-1 FIX: Odmiany słowa "adres" — w namesForms Morfeusza, błędnie
    // wykrywane jako imię ("adresem Lipowej" → OSOBA zamiast adres)
    "adres", "adresu", "adresem", "adresowi", "adresie",
    "adresów", "adresami", "adresach", "adresowy", "adresowa", "adresowe",
    "adresowego", "adresowej", "adresowym",
    // Odmiany "numer" — podobne ryzyko
    "numerem", "numerowi", "numerze", "numerów", "numerami",
    // TODO-2-FIX v1.7: Angielskie słowa w polskich dokumentach biznesowych.
    // Faktury, umowy i korespondencja z zagranicznymi kontrahentami zawierają
    // angielskie słowa które pasują do polskich wzorców imion/nazwisk/firm.
    // Przykłady z testów OCR: "ADDRESS"→OSOBA, "Bill"→OSOBA, "NOT"→FIRMA, "Poland"→FIRMA.
    // Lista obejmuje nagłówki faktur, opisy pól i spójniki angielskie.
    "address", "bill", "invoice", "total", "date", "from", "to", "due",
    "payment", "not", "for", "per", "page", "of", "and", "or", "the",
    "amount", "number", "vat", "net", "gross", "poland", "warsaw",
    "company", "name", "email", "phone", "city", "street", "country",
    "tax", "ref", "order", "item", "qty", "unit", "price", "subtotal",
    "description", "terms", "bank", "account", "swift", "iban",
    "due", "issued", "paid", "balance", "note", "notes", "attn",
    "subject", "re", "dear", "regards", "sincerely", "yours",
    // WHITE_LIST_FP v1.10: słowa pospolite i skróty z logów benchmarku B+C
    // Powtarzające się false positives — każde potwierdzone w raporcie benchmarku.
    // "zamieszkania"/"zamieszkały" — nagłówki adresowe; z wielką literą na początku
    // sekcji trafiają do NAME_FORWARD jako imię jeśli Morfeusz2 zna formę fleksyjną.
    "zamieszkania", "zamieszkały", "zamieszkała", "zamieszkałej", "zamieszkałego",
    "zamieszkałemu", "zamieszkałe",
    // "wydziału" i odmiany — brakuje prefiksu "wydział" w WHITE_LIST_INSTITUTIONS_PREFIX
    "wydziału", "wydziałem", "wydziałowi", "wydziałach",
    // Skróty 2-3 znakowe — artefakty OCR lub polskie skróty
    // "sp" = skrót "Spółka" przed formą prawną; "sł" = "służba"/"słownie"; "poe" = OCR artefakt
    // "pl" = skrót placu (pl. Wolności) — z wielką literą może trafić jako OSOBA
    "sp", "sł", "poe", "pl"
).map { it.lowercase() }.toHashSet()

// OSOBA_DENYLIST v1.11: słowa pospolite blokowane przed assignToken(..., TOKEN_OSOBA).
// Uzupełnienie WHITE_LIST_COMMON_WORDS o OCR-garbled warianty (bez polskich znaków)
// potwierdzonych w PSE_OSOBA logach benchmarku: "Wydzialu", "Wydziatu", "icznie", "SŁU".
// internal (nie private): OutputGuard.kt (Warstwa 6) reużywa tę samą listę, żeby nie
// flagować YELLOW dokładnie tych słów, które NameEngine już świadomie pomija przy maskowaniu.
internal val OSOBA_DENYLIST: Set<String> = setOf(
    "zamieszkania", "zameldowania", "służbowa", "służbowy",
    "wydział", "wydziału", "wydzialu", "wydziatu",
    "informacji", "informacj", "uzyskanych",
    "niejszej", "nin", "icznie",
    "sąd", "sądu", "rejonowy", "rejonowa", "okręgowy", "okręgowa", "skarbowy", "skarbowa",
    "sędzia", "sędziego", "sędzi",
    "urząd", "urzędu", "gminy", "gmina",
    "ulica", "ulicy", "adres", "adresu",
    "imię", "nazwisko", "pesel", "numer",
    "miejscowość", "miejscowości",
    // BUG-SLOWNIK-POSPOLITE-SLOWA (10.07): "rodo" to odmieniona forma prawdziwego,
    // rzadkiego nazwiska "Roda" w słowniku 39k (próg ≥100) — koliduje z akronimem RODO.
    "rodo",
    // BUG-SLOWNIK-POSPOLITE-SLOWA-IMIONA (11.07): krótkie imiona "Dana"/"Dato"/"Nika"/"Niko"
    // (słownik imion, próg ≥100) mają pełną odmianę przez Morfeusza pokrywającą się ze
    // zwykłymi polskimi słowami. Filtr semantyczny (MorfologikHelper.isDefinitelyNotPerson)
    // okazał się zbyt szeroki — blokował też "Tomka" (Morfologik zna je jako zwykły subst
    // we własnym słowniku, bez rozróżnienia na osobowe m1) — cofnięty na rzecz precyzyjnej
    // listy konkretnych potwierdzonych kolizji z benchmarku.
    "data", "dane", "danych", "nikach",
    // DECYZJA WŁAŚCICIELA (14.07): filtr semantyczny Morfologika (isCommonWordNotSurname,
    // usunięty niżej) blokował nie tylko te słowa, ale PRZY OKAZJI też realne nazwiska
    // będące nazwami zwierząt/przedmiotów (Zając/Wróbel/Sowa/Kot/Karaś) — one mają się
    // maskować ZAWSZE, nawet kosztem fałszywych trafień. Zamiast szerokiego filtra
    // gramatycznego (blokuje obie klasy naraz, nie da się ich odróżnić regułą), te 4
    // KONKRETNE, już potwierdzone słowa-kolizje (i ich odmiana) trafiają tu wprost —
    // ten sam wzorzec co "data/dane/danych/nikach" wyżej.
    "osoba", "osoby", "osobie", "osobę", "osobą", "osób", "osobom", "osobami", "osobach",
    "zapłata", "zapłaty", "zapłacie", "zapłatę", "zapłatą", "zapłat", "zapłatom",
    "zapłatami", "zapłatach",
    "łączna", "łącznej", "łączną", "łączny", "łączne", "łącznego", "łącznemu",
    "łącznym", "łącznych", "łącznymi",
    "działający", "działająca", "działające", "działającego", "działającej",
    "działającemu", "działającym", "działających", "działającymi", "działając",
    // BUG-MUSIAL-CZASOWNIK (14.07): wpis nazwiska "Musiał" w słowniku ma zanieczyszczoną
    // listę odmian — obok prawdziwych form nazwiska są tam też formy czasownika "musieć"
    // (błąd generatora, przypadkiem ten sam rdzeń). Tylko formy które są WYŁĄCZNIE
    // czasownikiem (nie mają żadnego prawdopodobnego odczytu jako nazwisko) — nie ruszam
    // "musiała/musiało/musiały", bo te mogłyby być realną odmianą nazwiska.
    "musi", "musisz", "musimy", "musicie", "muszę", "muszą",
)

// DECYZJA WŁAŚCICIELA (14.07): "Kot" (3 znaki) to prawdziwe nazwisko w słowniku, ale
// wszędzie indziej w pliku obowiązuje próg "≤3 znaki = skrót/artefakt OCR" (LENGTH-FIX,
// np. "Sp"/"Ko"/"pl") — bez wyjątku zostałoby jawne. Zamiast obniżać próg globalnie
// (więcej śmieci OCR jako fałszywe nazwiska), jawna lista PRAWDZIWYCH krótkich nazwisk —
// jedyny wyjątek od reguły długości, dopisywać kolejne tu gdy się pojawią, nie zmieniać
// progu globalnie.
internal val KNOWN_SHORT_SURNAMES: Set<String> = setOf("kot")

private val WHITE_LIST_CALENDAR: Set<String> = setOf(
    "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec",
    "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień",
    "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
    "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
    "poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela"
).map { it.lowercase() }.toHashSet()

private val WHITE_LIST_COUNTRIES: Set<String> = setOf(
    "polska", "niemcy", "francja", "włochy", "hiszpania", "portugalia",
    "wielka brytania", "irlandia", "niderlandy", "belgia", "luksemburg",
    "austria", "szwajcaria", "czechy", "słowacja", "węgry", "rumunia",
    "bułgaria", "grecja", "szwecja", "norwegia", "dania", "finlandia",
    "estonia", "łotwa", "litwa", "białoruś", "ukraina", "rosja",
    "stany zjednoczone", "usa", "kanada", "chiny", "japonia", "korea"
).map { it.lowercase() }.toHashSet()

// P7-FIX: Polskie miasta — zapobiega false positive OSOBA dla nazw miast
// Zawiera mianownik, dopełniacz i miejscownik (najczęstsze w dokumentach)
private val WHITE_LIST_CITIES: Set<String> = setOf(
    // Mianownik
    "warszawa", "kraków", "gdańsk", "wrocław", "poznań", "łódź", "katowice",
    "lublin", "białystok", "rzeszów", "szczecin", "bydgoszcz", "toruń",
    "kielce", "gliwice", "zabrze", "bytom", "olsztyn", "zielona góra", "opole",
    "gdynia", "częstochowa", "radom", "sosnowiec", "tychy", "rybnik",
    // Dopełniacz (z Krakowa, dla Warszawy, do Gdańska)
    "warszawy", "krakowa", "gdańska", "wrocławia", "poznania", "łodzi",
    "lublina", "białegostoku", "rzeszowa", "szczecina", "bydgoszczy", "torunia",
    "kielc", "gliwic", "zabrza", "bytomia", "olsztyna", "opola",
    "gdyni", "częstochowy", "radomia", "sosnowca",
    // Miejscownik (w Warszawie, w Krakowie, w Gdańsku)
    "warszawie", "krakowie", "gdańsku", "wrocławiu", "poznaniu", "łodzi",
    "lublinie", "białymstoku", "rzeszowie", "szczecinie", "bydgoszczy",
    "toruniu", "kielcach", "gliwicach", "zabrzu", "bytomiu", "olsztynie",
    "zielonej górze", "opolu", "gdyni",
    // CITIES-FIX v1.5: brakujący dopełniacz "Zielonej Góry" (z Zielonej Góry)
    "zielonej góry",
    // FLAGS-FIX-CITY v1.9: pojedyncze formy fleksyjne "Góra" — wcześniej tylko
    // wielosłowowe wpisy ("zielonej górze") nie chroniły samotnego "Górze"
    // przed VERB_ENDINGS w detectAlgorithmicFlags.
    "górze", "górę", "górą", "zielonej"
).map { it.lowercase() }.toHashSet()

// ============================================================
// Warstwa 5 — Końcówki czasownikowe (do wykrywania kontekstu)
// ============================================================
private val VERB_ENDINGS = Regex(
    """\b\w+(?:ał|ała|ało|ali|ały|uje|ujesz|ujemy|ujecie|ują|ie|isz|imy|icie|ią|ę|esz|emy|ecie|ą|ował|owała|owało|owali|owały|zuje|staje|zostaje|podpisał|podpisała|reprezentuje|oświadcza|zobowiązuje|potwierdza|zaakceptował|wskazał|wydał|orzekł)\b""",
    RegexOption.IGNORE_CASE
)

// ============================================================
// Przymiotniki wykluczone jako nazwiska
// Warstwa 1: MorfologikHelper.isAdjective() — pyta słownik morfologiczny (PoliMorf).
//   Zwraca true tylko gdy WSZYSTKIE tagi słowa to adj:* → zero false positives.
//   Prawdziwe nazwiska (Borowy, Nowakowa) są w słowniku jako subst lub adj+subst →
//   mixed tags → false → nie są blokowane.
// Warstwa 2: TITLE_ADJECTIVE_ENDINGS regex — fallback dla słów nieznanych Morfologikowi
//   (OCR-garbled: "Rejonow", "Skarbow") lub neologizmów.
// ============================================================
private val TITLE_ADJECTIVE_ENDINGS = Regex(
    """(?i)(?:owego|owej|owym|owych|iego|iej|iem|owy|owa|owe|ową|czny|czna|czne|cznego|cznej|wny|wna|wne|wnego|wnej)\b"""
)

// BUG-SLOWNIK-POSPOLITE-SLOWA (10.07) + BUG-ZAPLATY-CAPS-REGRESJA (12.07) + DECYZJA
// WŁAŚCICIELA (14.07): był tu filtr semantyczny (Morfologik "czy to na pewno nie osoba"),
// USUNIĘTY — blokował "Osoba"/"Zapłata"/"Łączna" (słusznie), ale PRZY OKAZJI blokował też
// realne nazwiska będące nazwami zwierząt/przedmiotów (Zając/Wróbel/Sowa/Kot/Karaś), bo
// gramatycznie nie da się ich odróżnić regułą — oba typy słów to zwykłe rzeczowniki/
// przymiotniki dla Morfologika. Właściciel: nazwisko ma się maskować ZAWSZE, nawet kosztem
// fałszywych trafień na słowach pospolitych. Te konkretne, potwierdzone kolizje (Osoba/
// Zapłata/Łączna/Działający) są teraz w OSOBA_DENYLIST (precyzyjna lista, nie szeroki
// filtr gramatyczny) — dokładnie ten sam wzorzec co "data/dane/danych/nikach" tamże.

private fun isAdjective(word: String): Boolean {
    // surnamesForms ZAWSZE przed Morfologikiem — "Kowalski" jest przymiotnikiem
    // dzierżawczym w słowniku morfologicznym, ale nazwiskiem w surnamesForms.
    if (LookupTables.initialized && LookupTables.surnamesForms.isNotEmpty()) {
        val w = word.lowercase()
        if (LookupTables.surnamesForms.contains(w)) return false
        if (LookupTables.surnamesForms.any { it.length >= 5 && w.startsWith(it) }) return false
    }
    // BUG-BARTLOMIEJ-PRZYMIOTNIK-FIX (11.07): namesForms ZAWSZE przed regex fallbackiem —
    // TITLE_ADJECTIVE_ENDINGS (Warstwa 2 niżej) szuka końcówki typu "iej" GDZIEKOLWIEK w
    // słowie (containsMatchIn, brak kotwicy początku), więc łapie ją jako substring w
    // imionach takich jak "Bartłomiej"/"Maciej" (kończą się na "...iej") i błędnie uznaje
    // je za przymiotnik ("wielkiej", "polskiej"). Realne imię ze słownika nigdy nie jest
    // przymiotnikiem — ten sam wzorzec ochronny co surnamesForms powyżej.
    if (LookupTables.initialized && LookupTables.namesForms.isNotEmpty()) {
        if (LookupTables.namesForms.contains(word.lowercase())) return false
    }
    // Warstwa 1: Morfologik — definitywny przymiotnik (nie-nazwisko)
    if (MorfologikHelper.isAdjective(word)) return true
    // Warstwa 2: regex fallback dla słów spoza słownika (OCR-garbled, neologizmy)
    return TITLE_ADJECTIVE_ENDINGS.containsMatchIn(word)
}

// BUG-DWA-IMIONA-FIX (11.07): NAME_FORWARD/BACKWARD/HONORIFIC_REGEX sklejają dwa
// sąsiadujące wyrazy z wielkiej litery w JEDEN token OSOBA (imię+nazwisko), zakładając
// że drugi wyraz to nazwisko. Gdy oba wyrazy są w rzeczywistości osobnymi imionami
// ("Paweł Tomasz", "Wiktoria Sylwia" — lista osób, nie jedna osoba dwuczłonowa),
// silnik błędnie łączy dwie osoby w jedną.
// BUG-DWA-IMIONA-FIX-v2 (11.07, po teście na telefonie): pierwsza wersja sprawdzała
// surnamesForms (39k) jako dowód "to jednak może być nazwisko" — ale ten sam zaszumiony,
// automatycznie zebrany rejestr PESEL zawiera "tomasz"/"maciej"/"piotr" jako RZADKIE
// nazwiska (próg ≥100), więc guard sam siebie wyłączał dokładnie dla najpopularniejszych
// imion, które najczęściej stoją obok siebie na listach osób. Nowa, prostsza reguła:
// drugi wyraz NIE jest traktowany jako nazwisko, jeśli jest w słowniku imion — bez
// pytania zaszumionego słownika nazwisk o zdanie.
// BUG-DWA-IMIONA-FIX-v3 (11.07, kolejny test na telefonie): v2 sprawdzała tylko
// POLISH_FIRST_NAMES (curated, ~150 pełnych imion) — nie łapała zdrobnień ("Tomek Kasia"
// nadal się sklejało, bo "Kasia" nie ma na tej krótkiej liście). LookupTables.namesForms
// (3,6k, ten sam rejestr PESEL/GUS co pełne imiona) zawiera zdrobnienia i jest czystym
// źródłem specjalnie dla imion (bez kolizji z nazwiskami, które psuły v1) — użyj go jako
// głównego sprawdzenia, POLISH_FIRST_NAMES zostaje jako fallback gdy słownik niezaładowany.
private fun isFirstNameOnlyNotSurname(word: String): Boolean {
    val lower = word.lowercase()
    return (LookupTables.initialized && LookupTables.namesForms.contains(lower)) ||
        POLISH_FIRST_NAMES.contains(lower)
}

// BUG-PROZA-SKLEJANIE-DOWOLNYCH-SLOW (11.07, diagnoza na realnym dokumencie właściciela):
// bloki łączące dwa słowa w OSOBA wymagały dictionary-dowodu TYLKO dla jednej strony
// (imię ze słownika) — druga strona ("nazwisko") mogła być DOWOLNYM słowem z wielkiej
// litery, byle nie przymiotnikiem/nie za krótkim/nie na denylist. W umowach to działa,
// bo "Jan Kowalski" — oba człony naprawdę są imieniem i nazwiskiem. W wolnym tekście
// (eseje, dokumenty techniczne) sąsiadem bywa dowolny wyraz z definicji terminu/nagłówka
// ("Zasada Pawła", "Teza Pawła") — regex zgarniał go bez pytania. Fix ogólny: druga
// strona też musi mieć dowód w słowniku nazwisk (surnamesForms) — dla złożonych nazwisk
// z myślnikiem ("Kowalska-Nowak") wystarczy że JEDEN człon jest potwierdzony.
private fun hasSurnameEvidence(word: String): Boolean {
    if (!LookupTables.initialized) return true  // brak słownika = nie blokuj (fallback jak gdzie indziej)
    return word.lowercase().split("-").any { LookupTables.surnamesForms.contains(it) }
}

// ============================================================
// Warstwa 3a — Cached regex do detekcji imion (budowany raz)
// ============================================================
private fun buildNamePattern(): String =
    if (LookupTables.initialized && LookupTables.namesForms.isNotEmpty())
        LookupTables.namesForms
            .filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
    else
        POLISH_FIRST_NAMES.joinToString("|") { Regex.escape(it) + "[a-ząćęłńóśźż]{0,5}" }

// S1/S2-TESTABILITY-FIX: Regex zależne od LookupTables.namesForms używają backing var
// zamiast `by lazy`. `by lazy` inicjuje się raz — przy zmianie słownika między testami
// stary pattern zostaje. Getter odbudowuje regex tylko gdy backing var jest null;
// resetRegexCache() zeruje go (wywoływane z teardown testów).
private var _nameForwardRegex: Regex? = null
private val NAME_FORWARD_REGEX: Regex
    get() {
        _nameForwardRegex?.let { return it }
        // BUG-DIAKRYTYKI-GRANICA (11.07, znaleziony testem sklejania): \b końcowy po grupie
        // nazwiska (wolny znak z klasy, nie literał ze słownika) obcinał ostatnią literę
        // gdy nazwisko/imię kończyło się diakrytykiem — Java \b traktuje "ł" jak nie-\w,
        // więc granica "wykrywała się" o jedną literę wcześniej ("Paweł"→"Pawe"). Ten sam
        // fix co StructuralEngine/AddressEngine/OutputGuard — $WORD_END_UNICODE zamiast \b.
        val r = Regex(
            """\b(${buildNamePattern()})\b[^\S\n]+([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöüÄÖÜ]+(?:-[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöü]+)*)$WORD_END_UNICODE""",
            RegexOption.IGNORE_CASE
        )
        if (LookupTables.initialized) _nameForwardRegex = r
        return r
    }

private var _nameBackwardRegex: Regex? = null
private val NAME_BACKWARD_REGEX: Regex
    get() {
        _nameBackwardRegex?.let { return it }
        // BUG-SKLEJANIE-MIEDZYLINIOWE-FIX (11.07, znalezione testem ręcznym na telefonie):
        // separator kończył się gołym \s+ (dopasowuje TEŻ \n) zamiast [^\S\n] jak wszędzie
        // indziej w tym pliku ("nie może przełknąć \n" — patrz komentarze przy innych
        // regexach). Efekt: nazwisko z KOŃCA jednej linii/akapitu sklejało się z imieniem
        // z POCZĄTKU zupełnie innej, niepowiązanej linii dalej w dokumencie ("Wiśniewska"
        // z jednej sekcji + "Bartłomiej" z zupełnie innej), bo \s+ przełykał puste linie
        // między nimi. Separator musi zostać w obrębie jednej linii.
        val r = Regex(
            """\b([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöüÄÖÜ]+(?:-[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöü]+)*)(?:[^\S\n]*,?[^\S\n]+)\b(${buildNamePattern()})$WORD_END_UNICODE""",
            RegexOption.IGNORE_CASE
        )
        if (LookupTables.initialized) _nameBackwardRegex = r
        return r
    }

private var _honorificRegex: Regex? = null
private val HONORIFIC_REGEX: Regex
    get() {
        _honorificRegex?.let { return it }
        val r = Regex(
            """\b(pan(?:i(?:a|ą|e|ej|ę)?|em|u|ie|a)?)[^\S\n]+(${buildNamePattern()})\b[^\S\n]+([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöüÄÖÜ]+(?:-[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöü]+)*)""",
            RegexOption.IGNORE_CASE
        )
        if (LookupTables.initialized) _honorificRegex = r
        return r
    }

// HONORIFIC_NAME_ONLY_REGEX: Pan/Pani + samo imię — bez wymaganego nazwiska.
// Negative lookahead zapobiega podwójnemu matchowaniu gdy po imieniu jest nazwisko
// (tym zajmuje się HONORIFIC_REGEX powyżej).
private var _honorificNameOnlyRegex: Regex? = null
private val HONORIFIC_NAME_ONLY_REGEX: Regex
    get() {
        _honorificNameOnlyRegex?.let { return it }
        val r = Regex(
            """\b(pan(?:i(?:a|ą|e|ej|ę)?|em|u|ie|a)?)[^\S\n]+(${buildNamePattern()})(?![^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż])""",
            RegexOption.IGNORE_CASE
        )
        if (LookupTables.initialized) _honorificNameOnlyRegex = r
        return r
    }

internal fun resetRegexCache() {
    _nameForwardRegex = null
    _nameBackwardRegex = null
    _honorificRegex = null
    _honorificNameOnlyRegex = null
}

// S3 — Inicjał + Nazwisko: "K. Kowalski" → OSOBA zamiast samego "Kowalski"
// Pojedynczy inicjał (wielka litera + kropka) + nazwisko ze słownika
private val INITIALS_REGEX = Regex(
    """\b([A-ZŁŚŹĆŃĄĘÓŻ]\.)[^\S\n]+([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{3,})\b"""
)

// REGEX-FIX v1.5: [^\S\n] zamiast \s w treści nazwy — poprzednia wersja
// mogła zszywać koniec jednego akapitu z formą prawną z następnego
// BUG-FIRMA-PRZECINEK-FIX (01.07, test ręczny): [.,] zamiast \. w formach prawnych —
// OCR myli kropkę z przecinkiem ("S,A," / "sp,j," / "Sp. z o.o,"). Ten regex biegnie
// PRZED detekcją nazwisk w applyContextualBlacklist (linia ~614) — jeśli nie rozpozna
// zdegradowanej formy prawnej, "Nowak"/"Wiśniewski" itd. wpadają dalej jako samo
// nazwisko (OSOBA), a reszta nazwy firmy zostaje jawna. AnchorEngine A.1 ma ten sam fix,
// ale to TEN regex (NameEngine, Runda 1) ma pierwszeństwo — bez obu fix nie działa.
private val FIRMA_LEGAL_REGEX = Regex(
    """[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z[^\S\n]]{2,50}[^\S\n]+(?:S[.,]A[.,]|Sp[.,]\s*z\s*o[.,]o[.,]|s[.,]c[.,]|Sp[.,]j[.,]|Sp[.,]k[.,]|S[.,]K[.,]A[.,]|P[.,]S[.,]A[.,]|LLC|GmbH|Ltd[.,]|LLP|B[.,]V[.,])""",
    RegexOption.IGNORE_CASE
)

// ============================================================
// Sprawdzenie białej listy
// ============================================================
internal fun isOnWhiteList(word: String): Boolean {
    val lower = word.lowercase()
    if (WHITE_LIST_INSTITUTIONS_PREFIX.any { lower.startsWith(it) }) return true  // prefix bezpieczny
    if (WHITE_LIST_INSTITUTIONS_EXACT.contains(lower)) return true                 // STRUCT-FIX: exact only
    if (WHITE_LIST_CONTRACTUAL.contains(lower)) return true
    if (WHITE_LIST_DOCUMENTS.contains(lower)) return true
    // LEGAL-FORM-FIX: krótkie formy (≤3 znaki, np. "ag", "ab", "kg") są podciągami
    // polskich słów ("magdaleny" zawiera "ag", "grabowski" zawiera "ab").
    // Dla form ≤3 znaki wymagamy dopasowania całego słowa lub końca zdania — nie substringu.
    if (WHITE_LIST_LEGAL_FORMS.any { form ->
        if (form.length >= 4 || form.contains('.')) lower.contains(form)
        else lower == form || lower.endsWith(" $form")
    }) return true
    if (WHITE_LIST_CALENDAR.contains(lower)) return true
    if (WHITE_LIST_COUNTRIES.contains(lower)) return true
    if (WHITE_LIST_CITIES.contains(lower)) return true         // P7-FIX
    if (WHITE_LIST_COMMON_WORDS.contains(lower)) return true
    if (lower.contains(' ')) {
        val allSafe = lower.split(' ').all { part ->
            WHITE_LIST_COMMON_WORDS.contains(part) ||
            WHITE_LIST_CALENDAR.contains(part) ||
            WHITE_LIST_COUNTRIES.contains(part) ||
            WHITE_LIST_CITIES.contains(part) ||               // P7-FIX
            WHITE_LIST_CONTRACTUAL.contains(part)
        }
        if (allSafe) return true
    }
    return false
}

// ============================================================
// TODO-2: Detekcja adresów z bazy GUS TERYT
// ============================================================
// BUG-DIAKRYTYKI-GRANICA (StructuralEngine.kt, WORD_END_UNICODE): \b końcowy zastąpiony —
// nazwa miasta po przyimku ("do Łodzi", "w Gdyni") może kończyć się polską literą diakrytyczną.
// BUG-CITY-PREP-CASE-FIX (08.07, brief Cursor): globalne (?i) na całym wzorcu znosiło wymóg
// wielkiej litery na DRUGIM (opcjonalnym) słowie miasta dwuwyrazowego — "w Toruń dnia" łapało
// "Toruń dnia" jako jeden kandydat (bo "dnia" pod IGNORE_CASE też pasuje do [A-ZŁŚŹĆŃĄĘÓŻ]),
// cityForms.contains("toruń dnia") zawodził, cały match odrzucony, "Toruń" zostawało jawne.
// (?i:...) ograniczone TYLKO do przyimka — reszta wzorca (Title-Case miasta) zostaje
// świadomie case-sensitive, zgodnie z przeznaczeniem.
// BUG-KEYWORD-CROSS-NEWLINE-FIX (08.07): \s w lookbehind -> [^\S\n] — przyimek na końcu
// linii + nazwa własna na początku ZUPEŁNIE INNEJ linii nie może być brana za "miasto po przyimku".
internal val CITY_PREP_REGEX = Regex(
    """(?<=\b(?i:w|z|do|ze|we|nad|pod|przy|przez|na)[^\S\n])([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]+(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]+)?)$WORD_END_UNICODE"""
)
// BUG-ADRES-MYSLNIK-FIX (04.07, Paweł): STREET_NAME_CHARS (StructuralEngine.kt) — wspólne
// źródło znaków nazwy ulicy. Współdzielone z AddressEngine.kt (Blok 1, STREET_DICT) — jedyny
// pozostały konsument po usunięciu applyStreetLookup (07.07, duplikat AddressEngine.STREET_DICT).
internal val STREET_CANDIDATE_REGEX = Regex(
    """\b([A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]+(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]+){0,2})[^\S\n]+(\d{1,4}[A-Za-z]?(?:[/[^\S\n]]\d{1,4}[A-Za-z]?)?)\b"""
)
private fun applyCityLookup(
    text: String,
    assignToken: (String, String) -> String
): String {
    if (!LookupTables.initialized || LookupTables.cityForms.isEmpty()) return text
    var result = text

    // Miasto przed kodem pocztowym: usunięte 04.07 (migracja ADRES krok 3) — już obsłużone
    // wcześniej w potoku przez StructuralEngine.applyPostalCityPatterns (Warstwa 1b, kierunek 2),
    // które działa PRZED tą funkcją. CITY_POSTAL_REGEX był tu martwy (TOKEN_RE guard blokował
    // go niemal zawsze, bo para kod+miasto była już tokenem zanim applyCityLookup ją zobaczył).

    // Miasto po przyimku: "w Warszawie", "z Gdańska" → ADRES
    result = CITY_PREP_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val city = match.value.trim()
        if (!LookupTables.cityForms.contains(city.lowercase())) return@replace match.value
        assignToken(city, TOKEN_ADRES)
    }

    return result
}

// Pola dowodu osobistego — maskują TYLKO wartość, etykieta zostaje w tekście.
// Obsługują ALL-CAPS (stary dowód) i mixed-case (nowy dowód).
// ID_CARD_PARENT przed FIRSTNAME — bardziej szczegółowy wzorzec ma pierwszeństwo.
// BUG-DIAKRYTYKI-GRANICA: \b końcowy -> WORD_END_UNICODE — imię może kończyć się diakrytykiem
// (np. "Stanisława", odmiana dopełniacza kończąca się na "ą"/"ę" itp.).
// BUG-KEYWORD-CROSS-NEWLINE-FIX (08.07): \s -> [^\S\n] — "imię (ojca/matki)" to kotwica,
// nie może przełknąć \n i ukraść imienia z zupełnie innej, niepowiązanej linii/akapitu.
private val ID_CARD_PARENT_REGEX = Regex(
    """(?i)\bimi[eę][^\S\n]+(?:ojca|matki|rodzica)[^\S\n]*:?[^\S\n]*([A-ZŁŚŹĆŃĄĘÓŻ][A-ZĄĆĘŁŃÓŚŹŻa-ząćęłńóśźż]{1,19})$WORD_END_UNICODE"""
)
private val ID_CARD_FIRSTNAME_REGEX = Regex(
    """(?i)\bimi[eę](?![^\S\n]+(?:ojca|matki|rodzica)\b)[^\S\n]*:?[^\S\n]*([A-ZŁŚŹĆŃĄĘÓŻ][A-ZĄĆĘŁŃÓŚŹŻa-ząćęłńóśźż]{1,19})$WORD_END_UNICODE"""
)

// PERF-FIX v1.6: titlePattern jako lazy val — poprzednio był kompilowany na nowo
// przy każdym wywołaniu applyContextualBlacklist(). Duża alternacja (~60 wpisów)
// jest kosztowna. FUNCTION_TITLES to stała — lazy val jest tu bezpieczny
// (brak ryzyka z kolejnością inicjalizacji jak w NAME_FORWARD_REGEX).
private val TITLE_PATTERN_REGEX: Regex by lazy {
    val titleStem = FUNCTION_TITLES.joinToString("|") { Regex.escape(it) + "[a-ząćęłńóśźż]{0,6}" }
    Regex(
        """($titleStem)[^\S\n]+([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöü]+(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżäöü]+)?)""",
        RegexOption.IGNORE_CASE
    )
}

// ============================================================
// Warstwa 3 — Czarna lista kontekstowa
// ============================================================
// BUG-27-FIX v1.8: usunięto @Suppress("UNUSED_PARAMETER").
// profileType jest CELOWO zachowany — onboarding przekazuje wartość ("general",
// "medical", "legal"); usunięcie parametru = breaking change gdy Potok-PROFIL
// dotrze. Suppress był martwym kodem, nie martwy był sam parametr.
// TODO: Potok-PROFIL — użyj profileType do modyfikacji progów detekcji
// i słowników (np. tryb "medical" powinien agresywniej łapać PESEL/PWZ).
internal fun applyContextualBlacklist(
    text: String,
    assignToken: (String, String) -> String,
    profileType: String  // TODO: Potok-PROFIL — aktualnie nieużywany
): String {
    var result = text

    // TODO-2: Adresy z bazy GUS TERYT — przed detekcją imion
    // applyStreetLookup usunięty 07.07 (konsolidacja ADRES) — ten sam słownik i kształt co
    // AddressEngine.STREET_DICT, duplikat strukturalny. applyCityLookup zostaje — to CITY_PREP
    // ("w Warszawie"), świadomie poza zakresem AddressEngine (przyimek + słownik miast, nie
    // ulica/kod pocztowy).
    result = applyCityLookup(result, assignToken)

    // 3a — Firmy z formą prawną
    // KLUCZOWE: forma prawna (Sp. z o.o., S.A., LLC...) jest silniejszym sygnałem
    // niż biała lista. "Centrum Medyczne MedHelp Sp. z o.o." → FIRMA mimo że
    // "centrum" jest na WHITE_LIST_INSTITUTIONS.
    // Biała lista blokuje TYLKO gdy NIE ma formy prawnej w tekście.
    result = FIRMA_LEGAL_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val hasLegalForm = LEGAL_FORM_CHECK_REGEX.containsMatchIn(match.value) ||  // P6-FIX: regex przed contains
            WHITE_LIST_LEGAL_FORMS.any { match.value.lowercase().contains(it) }
        if (!hasLegalForm && isOnWhiteList(match.value)) return@replace match.value
        assignToken(match.value.trim(), TOKEN_FIRMA)
    }

    // 3a — Honorifik + Imię + Nazwisko
    result = HONORIFIC_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val namePart = match.groupValues[2]
        val surnamePart = match.groupValues[3]
        // BUG-CASE-IGNORE-FORWARD-FIX (11.07): IGNORE_CASE na całym regexie sprawia że
        // [A-ZŁŚŹĆŃĄĘÓŻ] pasuje też do małej litery — bez tej kontroli runtime "Pan daty
        // wystawienia" (małe "d") przeszłoby jako imię/nazwisko. Ten sam mechanizm co
        // istniejący guard w NAME_BACKWARD_REGEX niżej ("CASE-FIX"), tu dotąd brakujący.
        if (!namePart[0].isUpperCase() || !surnamePart[0].isUpperCase()) return@replace match.value
        if (isOnWhiteList(surnamePart)) return@replace match.value
        if (!LookupTables.namesForms.contains(namePart.lowercase()) &&
            !POLISH_FIRST_NAMES.contains(namePart.lowercase())) return@replace match.value
        // BUG-DENYLIST-NAMEPART-FIX (11.07): OSOBA_DENYLIST był dotąd sprawdzany tylko po
        // stronie nazwiska — "Kowalski Data" ("Data" jako namePart ze słownika imion, kolizja
        // ze zwykłym słowem) przechodziło bez przeszkód mimo że "data" jest już na liście.
        if (namePart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        if (isFirstNameOnlyNotSurname(surnamePart)) return@replace match.value
        if (surnamePart.length < 4) return@replace match.value
        if (surnamePart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        if (!hasSurnameEvidence(surnamePart)) return@replace match.value
        "${match.groupValues[1]} ${assignToken("$namePart $surnamePart", TOKEN_OSOBA)}"
    }

    // 3a — Imię + Nazwisko (kolejność naturalna: Jan Kowalski)
    result = NAME_FORWARD_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val namePart = match.groupValues[1]
        val surname = match.groupValues[2]
        // BUG-CASE-IGNORE-FORWARD-FIX (11.07): tej kontroli tu brakowało (istniała już
        // w NAME_BACKWARD_REGEX niżej — "CASE-FIX"). IGNORE_CASE na regexie sprawia że
        // [A-ZŁŚŹĆŃĄĘÓŻ] pasuje też do małej litery — bez runtime guard "Data wystawienia"
        // (imię "Data" ze słownika + zwykłe słowo "wystawienia" małą literą) sklejało się
        // w jeden fałszywy token OSOBA. Realna nazwa własna zawsze zaczyna się wielką literą.
        if (!namePart[0].isUpperCase() || !surname[0].isUpperCase()) return@replace match.value
        // BUG-1 FIX: sprawdź białą listę dla OBIE grupy
        // "adresem Lipowej" — "adresem" jest na białej liście → nie tokenizuj
        if (isOnWhiteList(namePart)) return@replace match.value
        if (isOnWhiteList(surname)) return@replace match.value
        // BUG-DENYLIST-NAMEPART-FIX (11.07): jak w HONORIFIC_REGEX wyżej — namePart musi
        // przejść przez OSOBA_DENYLIST, nie tylko surname.
        if (namePart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        // BUG-2 FIX: Wyklucz przymiotniki TYLKO gdy nie są w słowniku nazwisk
        // "Kowalskiego" kończy się na -iego ale jest w surnamesForms → nazwisko
        // "Wielkiego" kończy się na -iego i NIE jest w surnamesForms → przymiotnik
        if (isAdjective(surname)) return@replace match.value
        if (isFirstNameOnlyNotSurname(surname)) return@replace match.value
        // LENGTH-FIX v1.10: fragment ≤3 znaki jako "nazwisko" to skrót lub artefakt OCR
        // ("Sp", "Ko", "pl") — nie jest realnym nazwiskiem. Wzorzec regex akceptuje ≥2 znaki.
        // Nie dotyczy namePart (imię) — buildNamePattern ma własny filtr ≥3 znaków.
        if (surname.length < 4) return@replace match.value
        if (surname.lowercase() in OSOBA_DENYLIST) return@replace match.value
        if (!hasSurnameEvidence(surname)) return@replace match.value
        assignToken(match.value, TOKEN_OSOBA)
    }

    // 3a — Nazwisko + Imię (kolejność odwrócona: Kowalski Jan)
    result = NAME_BACKWARD_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val surname = match.groupValues[1]
        val namePart = match.groupValues[2]
        // CASE-FIX: IGNORE_CASE sprawia że [A-Z] w regex pasuje do małej litery.
        // "imieniu Katarzyny" — 'i' (małe) pasuje do klasy [A-ZŁŚŹĆŃĄĘÓŻ] z IGNORE_CASE.
        // Realna nazwa własna zaczyna się wielką literą — odrzucamy małoliterowe "nazwiska".
        if (!surname[0].isUpperCase()) return@replace match.value
        // BUG-CASE-IGNORE-FORWARD-FIX (11.07): analogiczna kontrola dla namePart — tu też
        // brakowało jej (grupa 2, oparta na buildNamePattern(), nie miała żadnego guardu wielkości
        // litery w żadnym z bloków przed dzisiejszą sesją).
        if (!namePart[0].isUpperCase()) return@replace match.value
        // BUG-1 FIX: sprawdź białą listę dla obu grup
        if (isOnWhiteList(surname)) return@replace match.value
        if (isOnWhiteList(namePart)) return@replace match.value
        // BUG-DENYLIST-NAMEPART-FIX (11.07): "Kowalski Data" — "Data" jako namePart ze
        // słownika imion nigdy nie było sprawdzane przeciw OSOBA_DENYLIST w tym bloku.
        if (namePart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        // BUG-2 FIX: przymiotnik vs odmienione nazwisko
        if (isAdjective(surname)) return@replace match.value
        if (isFirstNameOnlyNotSurname(surname)) return@replace match.value
        // LENGTH-FIX v1.10: analogicznie jak w NAME_FORWARD — skróty 2-3 znakowe
        // nie są realnymi nazwiskami.
        if (surname.length < 4) return@replace match.value
        if (surname.lowercase() in OSOBA_DENYLIST) return@replace match.value
        if (!hasSurnameEvidence(surname)) return@replace match.value
        assignToken(match.value, TOKEN_OSOBA)
    }

    // 3a — Honorifik + samo imię (Pan Marek, Pani Halina) — bez wymaganego nazwiska
    result = HONORIFIC_NAME_ONLY_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val namePart = match.groupValues[2]
        // BUG-CASE-IGNORE-FORWARD-FIX (11.07): jak w pozostałych blokach — bez tego guardu
        // "Pan daty" (małe "d") przeszłoby jako imię.
        if (!namePart[0].isUpperCase()) return@replace match.value
        if (!LookupTables.namesForms.contains(namePart.lowercase()) &&
            !POLISH_FIRST_NAMES.contains(namePart.lowercase())) return@replace match.value
        if (isAdjective(namePart)) return@replace match.value
        if (namePart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        "${match.groupValues[1]} ${assignToken(namePart, TOKEN_OSOBA)}"
    }

    // 3a — Inicjał + Nazwisko (S3): "K. Kowalski" → OSOBA (całość w jednym tokenie)
    // Uruchamiany PRZED "Samo nazwisko" — blokuje podwójne tokenizowanie
    if (LookupTables.initialized && LookupTables.surnamesForms.isNotEmpty()) {
        result = INITIALS_REGEX.replace(result) { match ->
            if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
            val surname = match.groupValues[2]
            if (!LookupTables.surnamesForms.contains(surname.lowercase())) return@replace match.value
            if (isOnWhiteList(surname)) return@replace match.value
            if (surname.lowercase() in OSOBA_DENYLIST) return@replace match.value
            assignToken(match.value, TOKEN_OSOBA)
        }
    }

    // 3a-pre — Miasto dwuwyrazowe na przecięciu z nazwiskiem top-1000 (np. "Zielona Góra",
    // "Jelenia Góra"). MUSI biec PRZED "Samo nazwisko" niżej — inaczej drugi człon (np. "Góra",
    // realne nazwisko) dostaje token OSOBA zanim ten blok w ogóle zobaczy całą frazę.
    // BUG-GORA-OSOBA-FIX (04.07): lista LookupTables.citySurnameOverlap jest WĄSKA (44 słowa,
    // policzone programowo — generate_city_surname_overlap.py) — nie sprawdzamy całego
    // (bardzo dużego) cityForms tutaj, tylko te konkretne słowa gdzie kolizja z top-1000
    // nazwisk jest realna. Guard cityForms.contains(cała fraza) zapobiega myleniu prawdziwej
    // osoby "Jan Góra" z miastem — maskuje jako ADRES TYLKO gdy cała dwuwyrazowa fraza
    // faktycznie jest zarejestrowaną nazwą miejscowości.
    if (LookupTables.initialized && LookupTables.citySurnameOverlap.isNotEmpty()) {
        result = Regex("""\b([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,})[^\S\n]([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,})\b""")
            .replace(result) { match ->
                if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
                val second = match.groupValues[2].lowercase()
                if (!LookupTables.citySurnameOverlap.contains(second)) return@replace match.value
                if (!LookupTables.cityForms.contains(match.value.lowercase())) return@replace match.value
                assignToken(match.value, TOKEN_ADRES)
            }
    }

    // 3a — Krótkie prawdziwe nazwiska (KNOWN_SHORT_SURNAMES) — "Kot" itp., 3 znaki, poniżej
    // progu długości który reszta pliku traktuje jako skrót/artefakt OCR (patrz definicja
    // KNOWN_SHORT_SURNAMES wyżej). Osobny, węższy regex (dokładnie 1 wielka + 2 małe litery)
    // zamiast obniżania progu globalnie.
    result = Regex("""(?<![A-ZŁŚŹĆŃĄĘÓŻa-ząćęłńóśźż])([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2})(?![a-ząćęłńóśźż])""")
        .replace(result) { match ->
            if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
            val word = match.groupValues[1]
            if (word.lowercase() !in KNOWN_SHORT_SURNAMES) return@replace match.value
            if (isOnWhiteList(word)) return@replace match.value
            val token = assignToken(word, TOKEN_OSOBA)
            val before = result.getOrElse(match.range.first - 1) { ' ' }
            val after = result.getOrElse(match.range.last + 1) { ' ' }
            val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
            val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
            "$pre$token$suf"
        }

    // 3a — Samo nazwisko z surnamesForms (niski priorytet — po warstwach adresowych i firmowych)
    // BUG-ZIELONAGORA-FIX (04.07, diagnoza Cursor): pre/suf jak w AnchorEngine/ADDRESS —
    // obrona na wypadek gdyby jakiś inny krok potoku skleił to słowo z sąsiednim bez spacji
    // (root cause tego konkretnego przypadku było OCR_STREET_MIDSPACE, naprawione osobno).
    result = Regex("""(?<![A-ZŁŚŹĆŃĄĘÓŻ])([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{3,})(?![a-ząćęłńóśźż])""")
        .replace(result) { match ->
            if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
            val word = match.groupValues[1]
            if (!LookupTables.surnamesForms.contains(word.lowercase())) return@replace match.value
            if (isOnWhiteList(word)) return@replace match.value
            if (word.lowercase() in OSOBA_DENYLIST) return@replace match.value
            val token = assignToken(word, TOKEN_OSOBA)
            val before = result.getOrElse(match.range.first - 1) { ' ' }
            val after = result.getOrElse(match.range.last + 1) { ' ' }
            val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
            val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
            "$pre$token$suf"
        }

    // 3a — Samo imię z namesForms (najniższy priorytet — po nazwisku, przed tytułami)
    // BUG-SLOWNIK-POSPOLITE-SLOWA-IMIONA (11.07): rozszerzony słownik imion (3610, rejestr
    // PESEL/GUS) ma te same kolizje co rozszerzony słownik nazwisk 08.07 — krótkie imiona
    // (np. "Dana", "Dato", "Nika") mają pełną odmianę przez Morfeusza pokrywającą się ze
    // zwykłymi polskimi słowami ("dane", "data", "nikach"). Strażnik Morfologika
    // (isDefinitelyNotPerson) PRÓBOWANY i COFNIĘTY tego samego dnia — zbyt szeroki dla
    // imion: Morfologik zna wiele zdrobnień ("Tomka" — dopełniacz "Tomka") jako zwykły
    // rzeczownik we własnym słowniku (subst, bez rozróżnienia na osobowe m1), więc
    // zablokował realny golden test "Zadzwoniłem do Tomka wczoraj." W przeciwieństwie do
    // nazwisk (Warstwa "Samo nazwisko" niżej) imiona nie mają kształtu-sufiksu chroniącego
    // popularne przypadki przed Morfologikiem, więc zamiast filtra semantycznego —
    // precyzyjna lista potwierdzonych kolizji w OSOBA_DENYLIST (data/dane/danych/nikach).
    result = Regex("""(?<![A-ZŁŚŹĆŃĄĘÓŻ])([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,})(?![a-ząćęłńóśźż])""")
        .replace(result) { match ->
            if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
            val word = match.groupValues[1]
            if (!LookupTables.namesForms.contains(word.lowercase())) return@replace match.value
            if (isOnWhiteList(word)) return@replace match.value
            if (isAdjective(word)) return@replace match.value
            if (word.lowercase() in OSOBA_DENYLIST) return@replace match.value
            assignToken(word, TOKEN_OSOBA)
        }

    // 3b-IDCARD: Pola dowodu osobistego — etykieta zostaje, wartość maskowana.
    // "Imię ojca: STANISŁAW" → "Imię ojca: OSOBA_001"
    // "Imię: JAN" → "Imię: OSOBA_001"
    result = ID_CARD_PARENT_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val nameCapture = match.groupValues[1]
        val labelPart = match.value.dropLast(nameCapture.length)
        labelPart + assignToken(nameCapture, TOKEN_OSOBA)
    }
    result = ID_CARD_FIRSTNAME_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val nameCapture = match.groupValues[1]
        val labelPart = match.value.dropLast(nameCapture.length)
        labelPart + assignToken(nameCapture, TOKEN_OSOBA)
    }

    // 3b-CAPS: Samo nazwisko ALL-CAPS bez etykiety (stary dowód: "KOWALSKI" w linii).
    // Bramka surnamesForms — akronimy (PESEL, RODO, KRS, NIP) nie są w słowniku.
    if (LookupTables.initialized) {
        result = Regex("""\b([A-ZŁŚŹĆŃĄĘÓŻ]{4,20})\b""")
            .replace(result) { match ->
                if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
                val word = match.groupValues[1]
                if (!word.all { c -> c.isUpperCase() || !c.isLetter() }) return@replace match.value
                val lower = word.lowercase()
                if (!LookupTables.surnamesForms.contains(lower)) return@replace match.value
                if (isOnWhiteList(word)) return@replace match.value
                if (lower in OSOBA_DENYLIST) return@replace match.value
                assignToken(word, TOKEN_OSOBA)
            }
    }

    // 3b: Tytuł/funkcja → następne słowo z wielkiej litery
    result = TITLE_PATTERN_REGEX.replace(result) { match ->
        if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
        val namepart = match.groupValues[2]
        // BUG-1 FIX: sprawdź białą listę
        if (isOnWhiteList(namepart)) return@replace match.value
        // BUG-2 FIX: przymiotnik vs odmienione nazwisko
        if (isAdjective(namepart)) return@replace match.value
        // LENGTH-FIX v1.11: skróty i artefakty OCR ≤3 znaki (np. "SŁU", "pl") nie są osobami
        if (namepart.length < 4) return@replace match.value
        // DENYLIST v1.11: słowa pospolite mylone z OSOBA przez kontekst tytułu
        if (namepart.lowercase() in OSOBA_DENYLIST) return@replace match.value
        // LOOKUP-FIX: słowo po tytule musi być imieniem lub nazwiskiem ze słownika
        if (!LookupTables.namesForms.contains(namepart.lowercase()) &&
            !LookupTables.surnamesForms.contains(namepart.lowercase())) return@replace match.value
        val token = assignToken(namepart, TOKEN_OSOBA)
        "${match.groupValues[1]} $token"
    }

    return result
}

// ============================================================
// Warstwa 5 — Detekcja algorytmiczna (TYLKO FLAGI)
// ============================================================
// TOKEN_RE (\b) nie wykrywa tokenów przyklejonych do słów: "PanOSOBA_002"
// (Pan=\w, O=\w → brak boundary) lub "OSOBA_021OSOBA_011" (1=\w, O=\w → brak boundary).
// TOKEN_LOOSE_RE bez \b naprawia oba przypadki — używany tylko wewnątrz FLAGS.
private val TOKEN_LOOSE_RE = Regex("""(FIRMA|OSOBA|NUMER|EMAIL|KWOTA|ADRES)_\d{3}""")

// Artefakt sklejenia OCR: wielka litera w środku słowa po małej lub cyfrze.
// "URZĘDOWEUrząd" (E→U), "KrakowieWydział" (e→W), "ObywatelskichKraków" (h→K).
private fun hasMidUpperCase(word: String): Boolean {
    for (i in 1 until word.length) {
        if (word[i].isUpperCase() && (word[i - 1].isLowerCase() || word[i - 1].isDigit())) return true
    }
    return false
}

private val HONORIFICS: Set<String> = setOf(
    "pan", "pani", "pana", "panu", "panie", "panem", "panią", "panię", "panowie"
)

private fun personNameAllowlist(): Set<String> =
    if (LookupTables.initialized)
        LookupTables.namesForms + LookupTables.surnamesForms + POLISH_FIRST_NAMES
    else
        POLISH_FIRST_NAMES

private fun isPersonNamePart(word: String): Boolean =
    MorfologikHelper.isLikelyPersonNamePart(
        word,
        namesForms = if (LookupTables.initialized) LookupTables.namesForms else emptySet(),
        surnamesForms = if (LookupTables.initialized) LookupTables.surnamesForms else emptySet(),
        firstNamesFallback = POLISH_FIRST_NAMES
    )

private fun isDefinitelyNotPersonWord(word: String): Boolean =
    !isPersonNamePart(word) && MorfologikHelper.isDefinitelyNotPerson(word, personNameAllowlist())

internal fun detectAlgorithmicFlags(
    text: String,
    flags: MutableList<PseudonymFlag>,
    guardAllowlist: List<Pair<String, String>> = emptyList()
) {
    val sentences = text.split(Regex("""[.!?\n]\s*"""))
    val rawFlags = mutableListOf<PseudonymFlag>()
    val seenFragments = mutableSetOf<String>()

    for (sentence in sentences) {
        val words = sentence.split(Regex("""\s+""")).filter { it.isNotEmpty() }

        for (i in words.indices) {
            val word = words[i]

            // TOKEN_LOOSE_RE bez \b — wykrywa tokeny sklejone z innymi słowami
            if (TOKEN_LOOSE_RE.containsMatchIn(word)) continue
            if (!word[0].isUpperCase()) continue
            // Wielka litera w środku słowa po małej/cyfrze = sklejenie OCR ("URZĘDOWEUrząd")
            if (hasMidUpperCase(word)) continue
            if (isOnWhiteList(word)) continue
            if (i == 0) continue
            if (word.length < 2) continue
            if (word.all { c -> c.isUpperCase() || !c.isLetter() }) continue

            val cleanWord = word.trimEnd('.', ',', ';', ':', ')')
            if (cleanWord.length < 5) continue

            if (i + 1 < words.size) {
                val nextWord = words[i + 1]
                // cleanWord używany zamiast word — przecinek/kropka na końcu psuje isAdjective().
                // Flaguj tylko gdy Morfologik ZNA słowo lub jest w surnamesForms —
                // blokuje anglicyzmy i artefakty OCR ("Guard", "Manager" itp.).
                val isLikelySurname = LookupTables.initialized &&
                    LookupTables.surnamesForms.contains(cleanWord.lowercase())
                if (VERB_ENDINGS.matches(nextWord) &&
                    !isAdjective(cleanWord) &&
                    !isDefinitelyNotPersonWord(cleanWord) &&
                    isLikelySurname
                ) {
                    if (seenFragments.add(cleanWord.lowercase())) {
                        rawFlags.add(PseudonymFlag(
                            fragment = cleanWord,
                            reason = "Możliwe nazwisko — sprawdź czy chcesz zamaskować"
                        ))
                    }
                }
            }

            if (i + 1 < words.size) {
                val nextWord = words[i + 1]
                val cleanNextWord = nextWord.trimEnd('.', ',', ';', ':', ')')
                val nextIsValid = cleanNextWord.length >= 2 &&
                    nextWord[0].isUpperCase() &&
                    !nextWord.all { c -> c.isUpperCase() || !c.isLetter() } &&
                    // TOKEN_LOOSE_RE bez \b wykrywa "PanOSOBA_002" (boundary letter→letter brak)
                    !TOKEN_LOOSE_RE.containsMatchIn(nextWord) &&
                    // Sklejenie OCR w nextWord ("ObywatelskichKraków")
                    !hasMidUpperCase(cleanNextWord) &&
                    !isOnWhiteList(nextWord) &&
                    !isOnWhiteList("$cleanWord $cleanNextWord") &&
                    // Stanowisko jako pierwsze słowo pary → FUNCTION_TITLES ścieżka obsługuje osobno
                    !FUNCTION_TITLES.contains(cleanWord.lowercase()) &&
                    // Oczyść z interpunkcji przed isAdjective — "Społecznych," psuje lookup.
                    !isAdjective(cleanWord) &&
                    !isAdjective(cleanNextWord) &&
                    // Pozytywny dowód: co najmniej jedno słowo musi być imieniem lub nazwiskiem.
                    // Bez tego każda para słów z dużej litery (Funduszu Zdrowia, Custom Pak) staje się flagą.
                    (isPersonNamePart(cleanWord) || isPersonNamePart(cleanNextWord)) &&
                    !(isDefinitelyNotPersonWord(cleanWord) && isDefinitelyNotPersonWord(cleanNextWord))
                if (nextIsValid) {
                    val fragment = "$word $nextWord"
                    if (seenFragments.add(fragment.lowercase())) {
                        rawFlags.add(PseudonymFlag(
                            fragment = fragment,
                            reason = "Niezidentyfikowana nazwa własna — sprawdź"
                        ))
                    }
                }
            }

            if (FUNCTION_TITLES.contains(word.lowercase())) {
                // TOKEN_LOOSE_RE + trimEnd + whitelist — eliminuje "Naczelnik Wydziału,"
                // (Wydziału jest instytucjonalne, TOKEN_LOOSE_RE lapie sklejone tokeny)
                val contextWords = words.drop(i + 1).take(3)
                    .filter { !TOKEN_LOOSE_RE.containsMatchIn(it) }
                    .map { it.trimEnd('.', ',', ';', ':', ')') }
                    .filter { w ->
                        w.length >= 2 &&
                        !isOnWhiteList(w) &&
                        !hasMidUpperCase(w) &&
                        w.lowercase() !in HONORIFICS &&
                        isPersonNamePart(w)
                    }
                val context = contextWords.joinToString(" ")
                if (context.isNotEmpty() && !context.any { it.isDigit() }) {
                    val fragment = "$word $context".trim()
                    if (seenFragments.add(fragment.lowercase())) {
                        rawFlags.add(PseudonymFlag(
                            fragment = fragment,
                            reason = "Ta rola może identyfikować osobę — sprawdź",
                            isContextual = true
                        ))
                    }
                }
            }
        }
    }

    val prioritized = rawFlags
        .filter { flag ->
            guardAllowlist.none { (value, ruleType) ->
                ruleType == "OSOBA" && flag.fragment.equals(value, ignoreCase = true)
            }
        }
        .sortedWith(compareBy({ it.isContextual }, { rawFlags.indexOf(it) }))
    flags.addAll(prioritized)
}
