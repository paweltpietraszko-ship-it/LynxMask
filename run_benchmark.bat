@echo off
echo === LynxMask Benchmark ===

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

echo [0/4] Instalacja APK testowego...
call .\gradlew :app:installDebugAndroidTest

echo [1/4] Czyszczenie poprzednich wynikow...
adb shell rm -f /storage/emulated/0/Documents/LynxMask/benchmark_report.txt
adb shell rm -f /storage/emulated/0/Documents/LynxMask/benchmark_bugs.txt
adb shell rm -f /storage/emulated/0/Documents/LynxMask/missed_entities.html
adb shell rm -f /storage/emulated/0/Documents/LynxMask/ground_truth.json
adb shell rm -f /storage/emulated/0/Documents/LynxMask/ground_truth_lvl03.json

echo [2/4] Przepychanie datasetu...
adb push dataset\ground_truth_lvl01.json /sdcard/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl01.json
adb push dataset\ground_truth_lvl0.json  /sdcard/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl0.json
adb push dataset\ground_truth_lvl03.json /sdcard/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl03.json
adb push dataset\images /sdcard/Android/data/com.lynxmask.app/files/bench/images

echo [3/4] Uruchamianie benchmarku (czekaj ~30 sekund)...
adb shell am instrument -w -r -e class com.lynxmask.app.BenchmarkInstrumentedTest com.lynxmask.app.test/androidx.test.runner.AndroidJUnitRunner

echo [4/4] Pobieranie wynikow...
if not exist benchmark_results mkdir benchmark_results
adb pull /storage/emulated/0/Documents/LynxMask/benchmark_report.txt benchmark_results\benchmark_report.txt
adb pull /storage/emulated/0/Documents/LynxMask/benchmark_bugs.txt benchmark_results\benchmark_bugs.txt

echo === Gotowe. Wyniki w benchmark_results\ ===
pause
