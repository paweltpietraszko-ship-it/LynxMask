@echo off
echo === LynxMask Benchmark ===

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

echo [0/4] Instalacja APK (main + test)...
call .\gradlew :app:installDebug :app:installDebugAndroidTest

echo [1/4] Czyszczenie poprzednich wynikow...
adb shell rm -rf /storage/emulated/0/Documents/LynxMask/
adb shell rm -rf /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/
adb shell monkey -p com.lynxmask.app -c android.intent.category.LAUNCHER 1
timeout /t 2 /nobreak >nul

echo [2/4] Przepychanie datasetu (dataset_fresh)...
if not exist dataset_fresh\ground_truth.json (
    echo BLAD: Brak dataset_fresh\ground_truth.json — najpierw uruchom run_benchmark_fresh.bat
    pause
    exit /b 1
)
adb push dataset_fresh\ground_truth.json /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl03.json
adb push dataset_fresh\images /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/images

echo [3/4] Uruchamianie benchmarku (czekaj ~30 sekund)...
adb shell am instrument -w -r -e class com.lynxmask.app.BenchmarkInstrumentedTest com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner

echo [4/4] Pobieranie wynikow...
for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /format:list') do set DT=%%a
set TIMESTAMP=%DT:~0,4%-%DT:~4,2%-%DT:~6,2%_%DT:~8,2%%DT:~10,2%
set OUTDIR=benchmark_results\staly\%TIMESTAMP%
mkdir %OUTDIR%
adb pull /storage/emulated/0/Documents/LynxMask/benchmark_report.txt %OUTDIR%\benchmark_report.txt
adb pull /storage/emulated/0/Documents/LynxMask/benchmark_bugs.txt %OUTDIR%\benchmark_bugs.txt
adb pull /storage/emulated/0/Documents/LynxMask/benchmark_trace.txt %OUTDIR%\

echo === Gotowe. Wyniki w %OUTDIR%\ ===
pause
