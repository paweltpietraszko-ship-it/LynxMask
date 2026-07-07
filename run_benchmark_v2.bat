@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo === LynxMask Benchmark v2 (sekcje A-D, bramka OCR) ===

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "BENCH=/storage/emulated/0/Android/data/com.lynxmask.app/files/bench"
set "DOCS=/storage/emulated/0/Documents/LynxMask"

echo [0/4] Instalacja APK (main + test)...
call .\gradlew.bat :app:installDebug :app:installDebugAndroidTest
if errorlevel 1 (
    echo BLAD: Instalacja APK nie powiodla sie.
    pause
    exit /b 1
)

echo [1/4] Czyszczenie poprzednich wynikow na telefonie...
adb shell rm -rf /storage/emulated/0/Documents/LynxMask/
adb shell rm -rf /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/
adb shell monkey -p com.lynxmask.app -c android.intent.category.LAUNCHER 1 >nul 2>&1
timeout /t 2 /nobreak >nul

echo [2/4] Generowanie losowego datasetu (68 dok., 17 per poziom, bez seed)...
python generator.py --count 68 --output dataset_v2_run --max-level 3
if errorlevel 1 (
    echo BLAD: generator.py nie powiodl sie
    pause
    exit /b 1
)
adb push "dataset_v2_run\ground_truth.json" "%BENCH%/ground_truth_lvl03.json"
if errorlevel 1 (
    echo BLAD: adb push ground_truth.json
    pause
    exit /b 1
)
adb push "dataset_v2_run\images" "%BENCH%/images"
if errorlevel 1 (
    echo BLAD: adb push images
    pause
    exit /b 1
)

echo [3/4] Uruchamianie benchmarku v2 (runBenchmark + smoke)...
rem Cudzyslowy wokol class#method — bezpieczniejsze w cmd na Windows
adb shell am instrument -w -r -e class "com.lynxmask.app.BenchmarkInstrumentedTest#runBenchmark" com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner
set "TEST_EXIT=%ERRORLEVEL%"

echo [4/4] Pobieranie wynikow na PC...
rem for /f zamiast pliku temp — unika BOM z PowerShell redirect (psuje sciezke folderu)
for /f "delims=" %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set "TIMESTAMP=%%a"
set "OUTDIR=%~dp0benchmark_results\v2\%TIMESTAMP%"
if not exist "%~dp0benchmark_results\v2" mkdir "%~dp0benchmark_results\v2"
mkdir "%OUTDIR%" 2>nul
echo Folder docelowy: %OUTDIR%

rem NIE uzywaj: adb shell test -f — na Windows adb ZAWSZE zwraca 0 (znany bug).
rem Po prostu probuj pull z app-private bench, potem fallback Documents.

set "PULLED=0"
adb pull "%BENCH%/benchmark_report.txt" "%OUTDIR%\benchmark_report.txt"
if exist "%OUTDIR%\benchmark_report.txt" set "PULLED=1"

if "%PULLED%"=="0" (
    adb pull "%DOCS%/benchmark_report.txt" "%OUTDIR%\benchmark_report.txt"
    if exist "%OUTDIR%\benchmark_report.txt" set "PULLED=1"
)

if "%PULLED%"=="0" (
    echo.
    echo BLAD: Nie pobrano benchmark_report.txt do %OUTDIR%
    echo Test exit code: %TEST_EXIT%
    echo Sprawdz output z kroku [3/4] — szukaj FAIL, EngineSmoke, BENCH_FATAL.
    echo Reczny pull gdy plik jest na telefonie:
    echo   adb pull %BENCH%/benchmark_report.txt "%OUTDIR%\"
    pause
    exit /b 1
)

adb pull "%BENCH%/benchmark_bugs.txt" "%OUTDIR%\benchmark_bugs.txt"
if not exist "%OUTDIR%\benchmark_bugs.txt" adb pull "%DOCS%/benchmark_bugs.txt" "%OUTDIR%\benchmark_bugs.txt"
adb pull "%BENCH%/benchmark_trace.txt" "%OUTDIR%\"
if not exist "%OUTDIR%\benchmark_trace.txt" adb pull "%DOCS%/benchmark_trace.txt" "%OUTDIR%\"
adb pull "%BENCH%/missed_entities.html" "%OUTDIR%\missed_entities.html"
if not exist "%OUTDIR%\missed_entities.html" adb pull "%DOCS%/missed_entities.html" "%OUTDIR%\missed_entities.html"

echo.
echo === Gotowe. Wyniki v2 w: ===
echo %OUTDIR%
echo.
dir /b "%OUTDIR%"
echo.
if not "%TEST_EXIT%"=="0" (
    echo UWAGA: Test instrumented zglosil blad ^(exit %TEST_EXIT%^), ale raport pobrany.
    echo Sprawdz sekcje BLOKERY RELEASE w benchmark_report.txt
    echo.
)
type "%OUTDIR%\benchmark_report.txt"
echo.
pause
endlocal
