package com.lynxmask.app

// SessionStoreTest.kt — testy adversarialne (instrumented, androidTest/)
//
// Filozofia: każdy test próbuje ZŁAMAĆ SessionStore lub wymusić ciche przepuszczenie PII.
// Testy green = system pada we właściwy sposób (null zamiast crash, log zamiast wyjątek).
// Testy red = regresja bezpieczeństwa lub stabilności.
//
// Lokalizacja: app/src/androidTest/java/com/lynxmask/app/SessionStoreTest.kt
//
// Wymagane zależności (build.gradle.kts, androidTestImplementation):
//   androidx.test.ext:junit:1.1.5
//   androidx.test:core:1.5.0
//   kotlinx-coroutines-test (jeśli nie ma)

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Wyczyść stan między testami: singleton + plik DB + passphrase w SharedPrefs
        SessionStore.resetForTesting()
        ctx.getDatabasePath("sessions.db")?.delete()
        ctx.getSharedPreferences("lynxmask_session_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        SessionStore.resetForTesting()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HAPPY PATH — baseline żeby ataki miały punkt odniesienia
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun roundtrip_podstawowy_dziala() {
        val json = """{"OSOBA_001":"Jan Kowalski","NUMER_001":"12345678901"}"""
        SessionStore.save(ctx, "ABC123", json, 2)
        val loaded = SessionStore.loadTokenMap(ctx, "ABC123")

        assertNotNull("loadTokenMap null dla istniejącej sesji", loaded)
        assertEquals("Jan Kowalski", loaded!!["OSOBA_001"])
        assertEquals("12345678901", loaded["NUMER_001"])
    }

    @Test fun listSessions_posortowane_od_najnowszej() {
        SessionStore.save(ctx, "SES001", "{}", 0)
        Thread.sleep(20)
        SessionStore.save(ctx, "SES002", "{}", 0)
        Thread.sleep(20)
        SessionStore.save(ctx, "SES003", "{}", 0)

        val list = SessionStore.listSessions(ctx)
        assertEquals(3, list.size)
        assertEquals("SES003", list[0].sesjaId)
        assertEquals("SES001", list[2].sesjaId)
    }

    @Test fun deleteSession_usuwa_sesje_i_auditLog() {
        SessionStore.save(ctx, "DEL001", "{}", 0)
        SessionStore.recordAudit(ctx, "DEL001", "pseudonymized")
        SessionStore.deleteSession(ctx, "DEL001")

        assertNull("Sesja nadal istnieje po delete", SessionStore.loadTokenMap(ctx, "DEL001"))
        assertTrue("Sesja nadal w listSessions po delete",
            SessionStore.listSessions(ctx).none { it.sesjaId == "DEL001" })
        // audit_log też usunięty
        assertTrue(SessionStore.readAuditLogForTesting(ctx, "DEL001").isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 1 — SQL INJECTION przez sesjaId
    // Prepared statements muszą traktować wartość dosłownie, nie jako SQL.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun atak_sql_injection_w_sesjaId_nie_niszczy_tabeli() {
        val wredne = "'; DROP TABLE sessions; --"
        // Jeśli injection zadziała — tabela sessions zniknie, lista będzie crashować
        SessionStore.save(ctx, wredne, "{}", 0)
        SessionStore.save(ctx, "NORMALNY", "{}", 0)

        val list = SessionStore.listSessions(ctx)
        assertNotNull("Tabela sessions zniszczona przez injection", list)
        assertTrue("Normalna sesja nie istnieje po 'drop'",
            list.any { it.sesjaId == "NORMALNY" })
    }

    @Test fun atak_sql_injection_w_tokenMapJson_nie_wykonuje_sql() {
        // JSON zawiera SQL — musi być przechowany dosłownie (jako zaszyfrowany blob)
        val sqlWJson = """{"OSOBA_001":"'; DELETE FROM audit_log; --"}"""
        SessionStore.save(ctx, "INJECT002", sqlWJson, 1)
        SessionStore.recordAudit(ctx, "INJECT002", "pseudonymized")

        // audit_log musi nadal istnieć
        val audit = SessionStore.readAuditLogForTesting(ctx, "INJECT002")
        assertTrue("audit_log zniszczony przez injection w JSON", audit.isNotEmpty())

        // Wartość musi przetrwać roundtrip bez interpretacji
        val loaded = SessionStore.loadTokenMap(ctx, "INJECT002")
        assertEquals("'; DELETE FROM audit_log; --", loaded?.get("OSOBA_001"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 2 — KORUPCJA BLOBA
    // Ktoś (lub błąd I/O) nadpisał token_map_enc śmieciami.
    // loadTokenMap() MUSI zwrócić null, nie rzucić wyjątek ani zwrócić śmieci.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun atak_losowe_bajty_jako_blob_daja_null_nie_crash() {
        SessionStore.save(ctx, "CORRUPT1", """{"OSOBA_001":"Jan"}""", 1)
        // Nadpisz blob 50 losowymi bajtami — AES-GCM tag verification fail
        SessionStore.injectCorruptBlobForTesting(ctx, "CORRUPT1", ByteArray(50) { it.toByte() })

        val result = SessionStore.loadTokenMap(ctx, "CORRUPT1")
        assertNull("Skorumpowany blob powinien dać null, nie crash ani śmieci", result)
    }

    @Test fun atak_za_krotki_blob_daje_null_nie_crash() {
        SessionStore.save(ctx, "CORRUPT2", "{}", 0)
        // IV ma 12 bajtów — blob krótszy niż 12 musi być odrzucony przez require()
        SessionStore.injectCorruptBlobForTesting(ctx, "CORRUPT2", ByteArray(5) { 0x00 })

        val result = SessionStore.loadTokenMap(ctx, "CORRUPT2")
        assertNull("Za krótki blob powinien dać null", result)
    }

    @Test fun atak_pusty_blob_daje_null_nie_crash() {
        SessionStore.save(ctx, "CORRUPT3", "{}", 0)
        SessionStore.injectCorruptBlobForTesting(ctx, "CORRUPT3", ByteArray(0))

        val result = SessionStore.loadTokenMap(ctx, "CORRUPT3")
        assertNull("Pusty blob powinien dać null", result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 3 — DUPLIKAT sesjaId
    // INSERT OR REPLACE musi nadpisywać — nie tworzyć drugiego rekordu.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun duplikat_sesjaId_nadpisuje_nie_duplikuje() {
        SessionStore.save(ctx, "DUP001", """{"OSOBA_001":"Stara"}""", 1)
        SessionStore.save(ctx, "DUP001", """{"OSOBA_001":"Nowa"}""", 1)

        val loaded = SessionStore.loadTokenMap(ctx, "DUP001")
        assertEquals("Drugi save musi nadpisywać pierwszy", "Nowa", loaded!!["OSOBA_001"])

        val count = SessionStore.listSessions(ctx).count { it.sesjaId == "DUP001" }
        assertEquals("INSERT OR REPLACE stworzył duplikat", 1, count)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 4 — BRAKUJĄCE SESJE
    // Operacje na nieistniejących ID muszą być ciche — null lub no-op.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun load_nieistniejacej_sesji_zwraca_null() {
        val result = SessionStore.loadTokenMap(ctx, "BRAK_12345")
        assertNull("load nieistniejącej sesji musi zwracać null", result)
    }

    @Test fun delete_nieistniejacej_sesji_nie_crashuje() {
        // Nie może rzucić — cicha kontynuacja
        SessionStore.deleteSession(ctx, "NIE_MA_MNIE")
        // Jeśli doszliśmy tutaj — OK
    }

    @Test fun recordAudit_bez_sesji_w_tabeli_sessions_nie_crashuje() {
        // audit_log nie ma FK constraint — insert powinien działać
        SessionStore.recordAudit(ctx, "BRAK_SESJI", "pseudonymized")
        // Gdyby rzucił, test by padł
    }

    @Test fun listSessions_na_pustej_bazie_zwraca_pusta_liste() {
        val list = SessionStore.listSessions(ctx)
        assertNotNull(list)
        assertTrue("listSessions na pustej bazie powinna zwracać []", list.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 5 — EDGE CASE tokenMapJson
    // Puste, ogromne, specjalne znaki — wszystko musi przetrwać roundtrip.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun pusty_tokenMap_roundtrip() {
        SessionStore.save(ctx, "PUSTY001", "{}", 0)
        val loaded = SessionStore.loadTokenMap(ctx, "PUSTY001")
        assertNotNull("Pusty JSON nie powinien dać null", loaded)
        assertTrue("Pusta mapa powinna być pusta po roundtrip", loaded!!.isEmpty())
    }

    @Test fun cudzyslow_w_wartosci_przezywa_roundtrip() {
        val json = """{"OSOBA_001":"Jan \"Kowal\" Kowalski"}"""
        SessionStore.save(ctx, "QUOT001", json, 1)
        val loaded = SessionStore.loadTokenMap(ctx, "QUOT001")
        assertEquals("""Jan "Kowal" Kowalski""", loaded?.get("OSOBA_001"))
    }

    @Test fun polskie_znaki_przezyja_roundtrip() {
        val json = """{"OSOBA_001":"Łódź Śródmieście","ADRES_001":"ul. Żółtego 14"}"""
        SessionStore.save(ctx, "POLSKIE01", json, 2)
        val loaded = SessionStore.loadTokenMap(ctx, "POLSKIE01")
        assertEquals("Łódź Śródmieście", loaded?.get("OSOBA_001"))
        assertEquals("ul. Żółtego 14", loaded?.get("ADRES_001"))
    }

    @Test fun duzy_tokenMap_1000_tokenow_roundtrip() {
        val bigJson = (1..1000).joinToString(",", "{", "}") { i ->
            "\"OSOBA_${i.toString().padStart(3, '0')}\":\"Osoba $i Kowalski\""
        }
        SessionStore.save(ctx, "DUZY001", bigJson, 1000)
        val loaded = SessionStore.loadTokenMap(ctx, "DUZY001")

        assertNotNull("Duży tokenMap zwrócił null", loaded)
        assertEquals("Liczba tokenów po roundtrip", 1000, loaded!!.size)
        assertEquals("Osoba 500 Kowalski", loaded["OSOBA_500"])
        assertEquals("Osoba 1 Kowalski", loaded["OSOBA_001"])
        assertEquals("Osoba 1000 Kowalski", loaded["OSOBA_1000"])  // sprawdź że nie ucięto
    }

    @Test fun bardzo_dlugie_wartosci_tokenow_roundtrip() {
        // Wartość 2000 znaków — np. długi adres z OCR
        val longVal = "A".repeat(2000)
        val json = """{"OSOBA_001":"$longVal"}"""
        SessionStore.save(ctx, "LONG001", json, 1)
        val loaded = SessionStore.loadTokenMap(ctx, "LONG001")
        assertEquals(2000, loaded?.get("OSOBA_001")?.length)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 6 — RÓWNOLEGŁE ZAPISY
    // 20 coroutines jednocześnie — brak crash, brak korupcji danych.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun rownolegle_zapisy_20_coroutines_bez_korupcji() = runBlocking {
        val jobs = (1..20).map { i ->
            async(Dispatchers.Default) {
                val id = "ROW_${i.toString().padStart(3, '0')}"
                SessionStore.save(ctx, id, """{"OSOBA_001":"Osoba$i"}""", 1)
                SessionStore.recordAudit(ctx, id, "pseudonymized")
            }
        }
        jobs.awaitAll()

        val list = SessionStore.listSessions(ctx)
        assertEquals("Powinno być 20 sesji po równoległych zapisach", 20, list.size)

        val skorumpowane = (1..20).count { i ->
            val id = "ROW_${i.toString().padStart(3, '0')}"
            SessionStore.loadTokenMap(ctx, id)?.get("OSOBA_001") != "Osoba$i"
        }
        assertEquals("$skorumpowane sesji ma błędne dane po równoległych zapisach", 0, skorumpowane)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 7 — AUDIT LOG: zero PII
    // W audit_log mogą być tylko sesja_id i nazwa akcji — ZERO wartości tokenów.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun auditLog_nie_zawiera_wartosci_tokenow() {
        val json = """{"OSOBA_001":"Jan Kowalski","NUMER_001":"12345678901"}"""
        SessionStore.save(ctx, "AUDIT001", json, 2)
        SessionStore.recordAudit(ctx, "AUDIT001", "pseudonymized")
        SessionStore.recordAudit(ctx, "AUDIT001", "viewed")

        val auditEntries = SessionStore.readAuditLogForTesting(ctx, "AUDIT001")
        assertTrue("audit_log pusty — recordAudit nie zapisał", auditEntries.isNotEmpty())

        // Kluczowy test: żadna wartość PII nie może być w logu
        val auditText = auditEntries.joinToString(" ")
        assertFalse("PII w audit_log: Jan Kowalski", auditText.contains("Jan Kowalski"))
        assertFalse("PII w audit_log: PESEL", auditText.contains("12345678901"))
        assertTrue("action 'pseudonymized' nie ma w logu", auditText.contains("pseudonymized"))
        assertTrue("action 'viewed' nie ma w logu", auditText.contains("viewed"))
    }

    @Test fun auditLog_sesjaId_jest_tylko_identyfikatorem() {
        // sessionId to 6-znakowy kod (np. "A3F7B2") — nie PII
        // Sprawdź że sesjaId w audit_log jest dokładnie tym co przekazano
        SessionStore.save(ctx, "SESJA01", "{}", 0)
        SessionStore.recordAudit(ctx, "SESJA01", "pseudonymized")

        val audit = SessionStore.readAuditLogForTesting(ctx, "SESJA01")
        assertTrue("Wpis audit dla SESJA01 nie istnieje", audit.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 8 — IDEMPOTENTNOŚĆ init()
    // Wielokrotny init() na tej samej bazie nie może niszczyć danych.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun init_dwukrotny_nie_niszczy_danych() {
        SessionStore.save(ctx, "IDEM001", """{"OSOBA_001":"Test"}""", 1)
        SessionStore.init(ctx)  // drugi call — powinien być no-op (initialized=true)
        SessionStore.init(ctx)  // trzeci

        val loaded = SessionStore.loadTokenMap(ctx, "IDEM001")
        assertNotNull("Dane zniszczone po ponownym init()", loaded)
        assertEquals("Test", loaded!!["OSOBA_001"])
    }

    @Test fun init_po_resetForTesting_tworzy_czysta_baze() {
        SessionStore.save(ctx, "RESET001", "{}", 0)
        SessionStore.resetForTesting()
        ctx.getDatabasePath("sessions.db")?.delete()
        ctx.getSharedPreferences("lynxmask_session_store", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Po resetcie i usunięciu pliku — nowa baza, sesja nie istnieje
        val loaded = SessionStore.loadTokenMap(ctx, "RESET001")
        assertNull("Stara sesja istnieje po usunięciu pliku bazy", loaded)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATAK 9 — tokenCount vs rzeczywista zawartość
    // tokenCount to hint dla UI — nie musi zgadzać się z zawartością JSON.
    // Sprawdź że zapis z błędnym tokenCount nie niszczy sesji.
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun bledny_tokenCount_nie_niszczy_sesji() {
        val json = """{"OSOBA_001":"Jan","NUMER_001":"12345"}"""
        // Przekazujemy tokenCount=999 zamiast 2 — musi być zapisane bez crash
        SessionStore.save(ctx, "TCOUNT01", json, 999)

        val record = SessionStore.listSessions(ctx).find { it.sesjaId == "TCOUNT01" }
        assertNotNull(record)
        assertEquals("tokenCount zapisany jako podany", 999, record!!.tokenCount)
        // Dane tokenMap muszą być nienaruszone
        assertEquals("Jan", SessionStore.loadTokenMap(ctx, "TCOUNT01")?.get("OSOBA_001"))
    }
}
