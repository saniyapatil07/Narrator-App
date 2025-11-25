@echo off
echo ===================================
echo Narrator App - Log Viewer
echo ===================================
echo.
echo Select log level:
echo 1. All logs (Verbose)
echo 2. Debug and above
echo 3. Info and above
echo 4. Warnings and Errors only
echo 5. Errors only
echo 6. Save logs to file
echo 7. Clear logs and start fresh
echo.
set /p choice="Enter choice (1-7): "

if "%choice%"=="1" (
    echo.
    echo Showing ALL logs from Narrator App...
    echo Press Ctrl+C to stop
    echo.
    adb logcat -v time | findstr "narratorapp"
)

if "%choice%"=="2" (
    echo.
    echo Showing DEBUG logs and above...
    echo Press Ctrl+C to stop
    echo.
    adb logcat *:D | findstr "narratorapp"
)

if "%choice%"=="3" (
    echo.
    echo Showing INFO logs and above...
    echo Press Ctrl+C to stop
    echo.
    adb logcat *:I | findstr "narratorapp"
)

if "%choice%"=="4" (
    echo.
    echo Showing WARNINGS and ERRORS only...
    echo Press Ctrl+C to stop
    echo.
    adb logcat *:W | findstr "narratorapp"
)

if "%choice%"=="5" (
    echo.
    echo Showing ERRORS only...
    echo Press Ctrl+C to stop
    echo.
    adb logcat *:E
)

if "%choice%"=="6" (
    set filename=narrator_logs_%date:~-4,4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%.txt
    set filename=%filename: =0%
    echo.
    echo Saving logs to: %filename%
    echo Press Ctrl+C to stop logging
    echo.
    adb logcat -v time > %filename%
)

if "%choice%"=="7" (
    echo.
    echo Clearing old logs...
    adb logcat -c
    echo Logs cleared!
    echo.
    echo Starting fresh log stream...
    echo Press Ctrl+C to stop
    echo.
    adb logcat -v time | findstr "narratorapp"
)

pause