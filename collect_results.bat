@echo off
set SRC=C:\Projects\LynxMask\app\build\outputs\androidTest-results\connected\debug\SM-A536B - 16\logcat-com.lynxmask.app.BenchmarkInstrumentedTest-runBenchmark.txt
set OUT=C:\Projects\LynxMask\benchmark_results

if not exist "%OUT%" mkdir "%OUT%"

set STAMP=%date:~6,4%%date:~3,2%%date:~0,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set STAMP=%STAMP: =0%

copy "%SRC%" "%OUT%\log_%STAMP%.txt" > nul

echo.
findstr /i "BENCHMARK Recall Precis Krytyczne False" "%SRC%"
echo.
echo Pelny log: %OUT%\log_%STAMP%.txt
pause
