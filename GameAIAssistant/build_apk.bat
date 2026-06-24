@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   GameAI Assistant - APK Builder
echo ============================================
echo.

set "PROJECT_DIR=%~dp0"
set "TOOLS_DIR=%PROJECT_DIR%..\.tools"
set "JDK_DIR=%TOOLS_DIR%\jdk"
set "SDK_DIR=%TOOLS_DIR%\android-sdk"
set "GRADLE_DIR=%TOOLS_DIR%\gradle"
set "GRADLE_USER_HOME=%TOOLS_DIR%\.gradle"

:: === Find JDK ===
set "JAVA_HOME="
for /d %%d in ("%JDK_DIR%\*") do (
    if exist "%%d\bin\java.exe" set "JAVA_HOME=%%d"
)
if "%JAVA_HOME%"=="" (
    echo [ERROR] JDK not found at %JDK_DIR%
    echo Please ensure JDK 17 is downloaded to .tools\jdk\
    exit /b 1
)

echo [INFO] JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java" -version 2>&1

:: === Find Gradle ===
set "GRADLE_CMD="
for /d %%d in ("%GRADLE_DIR%\*") do (
    if exist "%%d\bin\gradle.bat" (
        set "GRADLE_CMD=%%d\bin\gradle"
        set "GRADLE_HOME=%%d"
    )
)
if "%GRADLE_CMD%"=="" (
    echo [ERROR] Gradle not found at %GRADLE_DIR%
    echo Please ensure Gradle is downloaded to .tools\gradle\
    exit /b 1
)

echo [INFO] GRADLE_HOME=%GRADLE_HOME%

:: === Android SDK setup ===
set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"

:: Move cmdline-tools to correct location if needed
if exist "%SDK_DIR%\cmdline-tools\cmdline-tools\bin\sdkmanager.bat" (
    move "%SDK_DIR%\cmdline-tools\cmdline-tools" "%SDK_DIR%\cmdline-tools\latest" >nul 2>&1
)

if not exist "%SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat" (
    echo [ERROR] Android SDK command-line tools not found!
    echo Expected: %SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat
    exit /b 1
)

echo [INFO] ANDROID_HOME=%ANDROID_HOME%

:: === Install required SDK components ===
echo.
echo [SDK] Checking required Android SDK components...
set "PATH=%JAVA_HOME%\bin;%SDK_DIR%\cmdline-tools\latest\bin;%SDK_DIR%\platform-tools;%PATH%"

:: Accept licenses (non-interactive)
echo y | call "%SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK_DIR%" --licenses >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] License acceptance had issues, continuing...
)

:: Install platform-tools, platform, build-tools
echo [SDK] Installing platform-tools, android-34, build-tools 34.0.0...
call "%SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK_DIR%" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Some SDK components may already be installed.
)

:: === Generate debug keystore ===
if not exist "%PROJECT_DIR%app\debug.keystore" (
    echo.
    echo [KEY] Generating debug keystore...
    "%JAVA_HOME%\bin\keytool" -genkey -v -keystore "%PROJECT_DIR%app\debug.keystore" ^
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass android -keypass android ^
        -dname "CN=Android Debug,O=Android,C=US" 2>nul
    if exist "%PROJECT_DIR%app\debug.keystore" (
        echo [OK] Debug keystore created.
    ) else (
        echo [WARN] Could not create keystore, will try without it.
    )
)

:: === Create local.properties ===
echo sdk.dir=%SDK_DIR:\=/%> "%PROJECT_DIR%local.properties"

:: === Generate Gradle wrapper if needed ===
if not exist "%PROJECT_DIR%gradlew.bat" (
    echo.
    echo [GRADLE] Generating Gradle wrapper...
    cd /d "%PROJECT_DIR%"
    call "%GRADLE_CMD%" wrapper --gradle-version 8.5 --no-daemon 2>&1
)

:: === Build ===
echo.
echo [BUILD] Starting Gradle build (this may take 5-10 minutes on first run)...
echo.
cd /d "%PROJECT_DIR%"

if exist "gradlew.bat" (
    call gradlew.bat clean assembleDebug --no-daemon --stacktrace
) else (
    call "%GRADLE_CMD%" clean assembleDebug --no-daemon --stacktrace
)

:: === Check result ===
echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    for %%f in ("app\build\outputs\apk\debug\app-debug.apk") do set "apksize=%%~zf"
    set /a "apksize_mb=!apksize! / 1048576"
    echo ============================================
    echo   BUILD SUCCESS!
    echo   APK: app\build\outputs\apk\debug\app-debug.apk
    echo   Size: !apksize_mb! MB
    echo ============================================
    echo.
    echo Install on phone:
    echo   1. Copy APK to phone via USB / cloud
    echo   2. Open APK file on phone to install
    echo   3. Enable "Install from unknown sources" if needed
    echo.
    echo Test step-by-step:
    echo   1. Launch GameAI Assistant
    echo   2. Grant screen recording permission
    echo   3. Tap "Start Capture" button
    echo   4. Open any game and play
    echo   5. Use "Simulate Score" to test scoring engine
    echo   6. Tap "Voice Test" to test TTS
) else (
    echo ============================================
    echo   BUILD FAILED!
    echo   Please check error messages above.
    echo ============================================
    exit /b 1
)

endlocal
