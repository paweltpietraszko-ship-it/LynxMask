@echo off
cd /d "%~dp0"
echo === LynxMask Benchmark v2 (sekcje A-D, bramka OCR) ===

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

set BENCH=/storage/emulated/0/Android/data/com.lynxmask.app/files/bench
set DOCS=/storage/emulated/0/Documents/LynxMask

echo [0/4] Instalacja APK (main + test)...
call .\gradlew :app:installDebug :app:installDebugAndroidTest

echo [1/4] Czyszczenie poprzednich wynikow...
adb shell rm -rf /storage/emulated/0/Documents/LynxMask/
adb shell rm -rf /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/
adb shell monkey -p com.lynxmask.app -c android.intent.category.LAUNCHER 1
timeout /t 2 /nobreak >nul

echo [2/4] Przepychanie datasetu stalego (dataset_staly, ground_truth_lvl03.json)...
if not exist dataset_staly\ground_truth.json (
    echo BLAD: Brak dataset_staly\ground_truth.json
    echo Jednorazowa inicjacja: xcopy dataset_fresh dataset_staly /E /I
    pause
    exit /b 1
)
adb push dataset_staly\ground_truth.json %BENCH%/ground_truth_lvl03.json
adb push dataset_staly\images %BENCH%/images

echo [3/4] Uruchamianie benchmarku v2...
echo   Smoke test (regex ICU) + runBenchmark (sekcje B/C/D)
adb shell am instrument -w -r -e class com.lynxmask.app.BenchmarkInstrumentedTest com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner

echo [4/4] Pobieranie wynikow...
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set TIMESTAMP=%%a
set OUTDIR=benchmark_results\staly\%TIMESTAMP%
if not exist "benchmark_results\staly" mkdir "benchmark_results\staly"
mkdir "%OUTDIR%"

adb shell test -f %BENCH%/benchmark_report.txt
if errorlevel 1 (
    echo.
    echo BLAD: Brak %BENCH%/benchmark_report.txt — test mogl sie nie powiesc.
    echo Sprawdz output z kroku [3/4] (regexSmokeTest lub runBenchmark).
    pause
    exit /b 1
)

adb pull %BENCH%/benchmark_report.txt "%OUTDIR%\benchmark_report.txt"
if errorlevel 1 adb pull %DOCS%/benchmark_report.txt "%OUTDIR%\benchmark_report.txt"
adb pull %BENCH%/benchmark_bugs.txt "%OUTDIR%\benchmark_bugs.txt"
if errorlevel 1 adb pull %DOCS%/benchmark_bugs.txt "%OUTDIR%\benchmark_bugs.txt"
adb pull %BENCH%/benchmark_trace.txt "%OUTDIR%\"
if errorlevel 1 adb pull %DOCS%/benchmark_trace.txt "%OUTDIR%\"
adb pull %BENCH%/missed_entities.html "%OUTDIR%\"
if errorlevel 1 adb pull %DOCS%/missed_entities.html "%OUTDIR%\"

if not exist "%OUTDIR%\benchmark_report.txt" (
    echo.
    echo BLAD: Nie udalo sie pobrac benchmark_report.txt do %OUTDIR%
    pause
    exit /b 1
)

echo.
echo === Gotowe. Wyniki v2 w %OUTDIR%\ ===
echo.
type "%OUTDIR%\benchmark_report.txt"
echo.
pause
