@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set LATEST=benchmark_results\stress\latest
if not exist "%LATEST%" mkdir "%LATEST%"

rem Pelny log uruchomienia — zostaje po zamknieciu okna
set RUN_LOG=%LATEST%\run.log
echo.>> "%RUN_LOG%"
echo ============================================================>> "%RUN_LOG%"
echo STRESS benchmark %date% %time%>> "%RUN_LOG%"
echo ============================================================>> "%RUN_LOG%"

echo === LynxMask STRESS Benchmark (Arena Cursor vs Claude Code) ===
echo Wyniki zawsze w: %LATEST%\
echo Pelny log: %RUN_LOG%
echo.

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

rem --- [0] Dataset ---
set REGEN=0
if /I "%~1"=="--regen" set REGEN=1
if /I "%~1"=="-r" set REGEN=1

if %REGEN%==1 (
    echo [0/3] Regeneracja dataset_stress (300 dok., seed 42, duze pule)...
    echo [0/3] Regeneracja dataset_stress>> "%RUN_LOG%"
    python generator_stress.py --count 300 --output dataset_stress --seed 42 >> "%RUN_LOG%" 2>&1
    if errorlevel 1 (
        echo BLAD: generator_stress.py nie powiodl sie.
        echo BLAD generator>> "%RUN_LOG%"
        echo Log: %RUN_LOG%
        pause
        exit /b 1
    )
) else if not exist dataset_stress\ground_truth.json (
    echo [0/3] Brak dataset_stress — generuje pierwszy raz...
    echo [0/3] Pierwsza generacja dataset_stress>> "%RUN_LOG%"
    python generator_stress.py --count 300 --output dataset_stress --seed 42 >> "%RUN_LOG%" 2>&1
    if errorlevel 1 (
        echo BLAD: generator_stress.py nie powiodl sie.
        echo BLAD generator>> "%RUN_LOG%"
        echo Log: %RUN_LOG%
        pause
        exit /b 1
    )
) else (
    echo [0/3] Dataset OK: dataset_stress\ground_truth.json
    echo [0/3] Dataset OK>> "%RUN_LOG%"
)

echo.
echo [1/3] Uruchamianie StressBenchmarkTest (JVM, bez telefonu)...
echo [1/3] Gradle test>> "%RUN_LOG%"
call .\gradlew.bat :app:testDebugUnitTest --tests "com.lynxmask.app.StressBenchmarkTest" >> "%RUN_LOG%" 2>&1
set TEST_EXIT=%ERRORLEVEL%

echo.
echo [2/3] Odczyt wynikow z %LATEST%\ ...
set LATEST_REPORT=%LATEST%\report.txt
set LATEST_ARENA=%LATEST%\arena_score.txt

if exist "%LATEST_REPORT%" (
    echo.
    echo === RAPORT (report.txt) ===
    type "%LATEST_REPORT%"
    echo.
    echo Raport zapisany: %LATEST_REPORT%
    echo Raport: %LATEST_REPORT%>> "%RUN_LOG%"
) else (
    echo UWAGA: Brak %LATEST_REPORT% — test mogl sie nie wykonac.
    echo Brak report.txt>> "%RUN_LOG%"
)

if exist "%LATEST_ARENA%" (
    echo.
    echo === ARENA ===
    type "%LATEST_ARENA%"
    type "%LATEST_ARENA%">> "%RUN_LOG%"
)

echo.
if %TEST_EXIT%==0 (
    echo [3/3] WYNIK ARENY: CC +1 ^(test PASSED — kod przeszedl progi Cursora^)
    echo WYNIK: CC +1 PASSED>> "%RUN_LOG%"
) else (
    echo [3/3] WYNIK ARENY: CURSOR +1 ^(test FAILED — kod nie przeszedl progow^)
    echo       Szczegoly: build\reports\tests\testDebugUnitTest\index.html
    echo WYNIK: CURSOR +1 FAILED>> "%RUN_LOG%"
)

echo.
echo Scoreboard: benchmark_results\arena_scoreboard.txt
if exist benchmark_results\arena_scoreboard.txt (
    type benchmark_results\arena_scoreboard.txt
    type benchmark_results\arena_scoreboard.txt>> "%RUN_LOG%"
)

echo.
echo --- Pliki zostaja na dysku ---
echo   %LATEST%\report.txt
echo   %LATEST%\arena_score.txt
echo   %LATEST%\last_run.txt
echo   %RUN_LOG%
if exist "%LATEST%\last_run.txt" (
    echo.
    echo Archiwum z timestampem:
    type "%LATEST%\last_run.txt"
)

echo.
pause
exit /b %TEST_EXIT%
