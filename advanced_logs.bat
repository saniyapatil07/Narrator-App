@echo off
cls
echo ===================================
echo Narrator App - Advanced Log Viewer
echo ===================================
echo.
echo Choose component to monitor:
echo.
echo 1. MainActivity (App lifecycle)
echo 2. Camera System (CameraX, Analyzer)
echo 3. Face Recognition (FaceRecognizer, FaceDetector)
echo 4. Voice Commands (VoiceCommandService)
echo 5. Navigation (ARCore, NavigationEngine)
echo 6. Memory Manager (Database, Embeddings)
echo 7. All Components
echo 8. Errors Only (All components)
echo 9. Save Full Log to File
echo.
set /p choice="Enter choice (1-9): "

adb logcat -c
echo.
echo Log stream started... Press Ctrl+C to stop
echo ===================================
echo.

if "%choice%"=="1" (
    adb logcat -v time -s MainActivity:V AndroidRuntime:E
)

if "%choice%"=="2" (
    adb logcat -v time -s CameraXManager:D CombinedAnalyzer:D OverlayView:D ImageUtils:D AndroidRuntime:E
)

if "%choice%"=="3" (
    adb logcat -v time -s FaceRecognizer:D FaceDetector:D MemoryManager:D RecognitionDatabase:D AndroidRuntime:E
)

if "%choice%"=="4" (
    adb logcat -v time -s VoiceCommandService:D VoiceCommandManager:D TTSManager:D AndroidRuntime:E
)

if "%choice%"=="5" (
    adb logcat -v time -s ARCoreManager:D NavigationEngine:D Waypoint:D AndroidRuntime:E
)

if "%choice%"=="6" (
    adb logcat -v time -s MemoryManager:D RecognitionDatabase:D EmbeddingDao:D AndroidRuntime:E
)

if "%choice%"=="7" (
    adb logcat -v time | findstr /i "narratorapp MainActivity Camera Face Voice Navigation Memory Recognition ARCore"
)

if "%choice%"=="8" (
    adb logcat -v time *:E
)

if "%choice%"=="9" (
    set filename=narrator_full_log_%date:~-4,4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%.txt
    set filename=%filename: =0%
    echo Saving to: %filename%
    echo.
    adb logcat -v time > "%filename%"
)

pause