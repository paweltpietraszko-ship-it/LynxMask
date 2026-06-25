@echo off
cd /d "%~dp0"
echo === LynxMask Benchmark ===

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

rem Źródło raportów: test ZAWSZE zapisuje do app-private bench (nie do Documents).
set BENCH=/storage/emulated/0/Android/data/com.lynxmask.app/files/bench
rem Fallback: kopia do Documents (może nie powstać na Androidzie 16+).
set DOCS=/storage/emulated/0/Documents/LynxMask

echo [0/4] Instalacja APK (main + test)...
call .\gradlew :app:installDebug :app:installDebugAndroidTest

echo [1/4] Czyszczenie poprzednich wynikow...
adb shell rm -rf /storage/emulated/0/Documents/LynxMask/
adb shell rm -rf /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/
adb shell monkey -p com.lynxmask.app -c android.intent.category.LAUNCHER 1
timeout /t 2 /nobreak >nul

echo [2/4] Przepychanie datasetu stalego (dataset_staly)...
if not exist dataset_staly\ground_truth.json (
    echo BLAD: Brak dataset_staly\ground_truth.json
    echo Jednorazowa inicjacja: xcopy dataset_fresh dataset_staly /E /I
    pause
    exit /b 1
)
adb push dataset_staly\ground_truth.json /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl03.json
adb push dataset_staly\images /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/images

echo [3/4] Uruchamianie benchmarku (czekaj ~30 sekund)...
adb shell am instrument -w -r -e class com.lynxmask.app.BenchmarkInstrumentedTest com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner

echo [4/4] Pobieranie wynikow...
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmm"') do set TIMESTAMP=%%a
set OUTDIR=benchmark_results\staly\%TIMESTAMP%
if not exist "benchmark_results\staly" mkdir "benchmark_results\staly"
mkdir "%OUTDIR%"

rem Sprawdz czy test zapisal raporty na telefonie
adb shell test -f %BENCH%/benchmark_report.txt
if errorlevel 1 (
    echo.
    echo BLAD: Brak %BENCH%/benchmark_report.txt na telefonie.
    echo Test mogl sie nie powiesc — sprawdz output z kroku [3/4].
    echo Fallback: uruchom collect_results.bat ^(logcat^).
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
