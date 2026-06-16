@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

set ADB="C:\Users\p_pie\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set PROJECTS=C:\Projects\LynxMask

echo.
echo  Dostepne datasety:
echo  ------------------
set i=0
for /d %%D in ("%PROJECTS%\dataset_*") do (
    set /a i+=1
    set "dir_!i!=%%D"
    set "name_!i!=%%~nxD"
    echo  [!i!] %%~nxD
)

if %i%==0 (
    echo  Brak folderow dataset_* w %PROJECTS%
    pause & exit /b 1
)

echo.
set /p choice="  Wybierz numer datasetu: "

if "%choice%"=="" ( echo Anulowano. & pause & exit /b 1 )
if !choice! LSS 1 ( echo Zly numer. & pause & exit /b 1 )
if !choice! GTR %i% ( echo Zly numer. & pause & exit /b 1 )

set DATASET=!dir_%choice%!
set DSNAME=!name_%choice%!
set BENCH=/sdcard/Android/data/com.lynxmask.app/files/bench

echo.
echo  Dataset: %DSNAME%
echo.

echo [0/3] Czyszcze stare dane...
%ADB% shell rm -rf %BENCH%/images
%ADB% shell rm -f %BENCH%/ground_truth.json
%ADB% shell rm -f %BENCH%/benchmark_report.txt
%ADB% shell rm -f %BENCH%/benchmark_bugs.txt
%ADB% shell mkdir -p %BENCH%
rem UWAGA: NIE tworzymy folderu images przed pushem.
rem Gdy images juz istnieje, ADB push wklada folder WEWNATRZ -> images/images/*.png
rem Gdy images nie istnieje, ADB push tworzy go i wklada zawartosc poprawnie.

echo [1/3] Wgrywam obrazy...
%ADB% push "%DATASET%\images" %BENCH%/images

echo [2/3] Wgrywam ground_truth.json...
%ADB% push "%DATASET%\ground_truth.json" %BENCH%/ground_truth.json

echo [3/3] Weryfikacja...
%ADB% shell ls %BENCH%/images | find /c ".png"

echo.
echo Gotowe. Teraz odpal test w Android Studio.
pause
