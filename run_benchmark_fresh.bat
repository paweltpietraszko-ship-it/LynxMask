@echo off
cd /d "%~dp0"
echo === LynxMask Benchmark (fresh dataset) ===

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

set BENCH=/storage/emulated/0/Android/data/com.lynxmask.app/files/bench
set DOCS=/storage/emulated/0/Documents/LynxMask

echo [0/4] Instalacja APK testowego...
call .\gradlew :app:installDebugAndroidTest

echo [1/4] Czyszczenie poprzednich wynikow...
adb shell rm -rf /storage/emulated/0/Documents/LynxMask/
adb shell rm -rf /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/
adb shell monkey -p com.lynxmask.app -c android.intent.category.LAUNCHER 1
timeout /t 2 /nobreak >nul

echo [1b/4] Generowanie swiezego datasetu (68 dok., 17 per poziom, losowy seed)...
python generator.py --count 68 --output dataset_fresh_run --max-level 3
if errorlevel 1 (
    echo BLAD: generator.py nie powiodl sie
    pause
    exit /b 1
)

echo [2/4] Przepychanie datasetu...
adb push dataset_fresh_run\ground_truth.json /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl03.json
adb push dataset_fresh_run\images /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/images

echo [3/4] Uruchamianie benchmarku (czekaj ~30 sekund)...
adb shell am instrument -w -r -e class com.lynxmask.app.BenchmarkInstrumentedTest com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner

echo [4/4] Pobieranie wynikow...
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set TIMESTAMP=%%a
set OUTDIR=benchmark_results\fresh\%TIMESTAMP%
if not exist "benchmark_results\fresh" mkdir "benchmark_results\fresh"
mkdir "%OUTDIR%"

adb shell test -f %BENCH%/benchmark_report.txt
if errorlevel 1 (
    echo.
    echo BLAD: Brak %BENCH%/benchmark_report.txt na telefonie.
    echo Test mogl sie nie powiesc — sprawdz output z kroku [3/4].
    pause
    exit /b 1
)

adb pull %BENCH%/benchmark_report.txt "%OUTDIR%\benchmark_report.txt"
if errorlevel 1 adb pull %DOCS%/benchmark_report.txt "%OUTDIR%\benchmark_report.txt"
adb pull %BENCH%/benchmark_bugs.txt "%OUTDIR%\benchmark_bugs.txt"
if errorlevel 1 adb pull %DOCS%/benchmark_bugs.txt "%OUTDIR%\benchmark_bugs.txt"
adb pull %BENCH%/benchmark_trace.txt "%OUTDIR%\"
if errorlevel 1 adb pull %DOCS%/benchmark_trace.txt "%OUTDIR%\"

if not exist "%OUTDIR%\benchmark_report.txt" (
    echo.
    echo BLAD: Nie udalo sie pobrac benchmark_report.txt do %OUTDIR%
    pause
    exit /b 1
)

echo === Gotowe. Wyniki w %OUTDIR%\ ===
dir /b "%OUTDIR%"
pause
