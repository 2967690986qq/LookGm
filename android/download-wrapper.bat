@echo off
echo ==========================================
echo   LookGm - 下载 Gradle Wrapper
echo ==========================================
echo.
echo 正在下载 gradle-wrapper.jar...
echo.

:: 下载 React Native 兼容的 gradle-wrapper.jar
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/facebook/react-native/0.73.6/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar' -UseBasicParsing; Write-Host '[OK] gradle-wrapper.jar 已保存' }"

echo.
echo 下载完成！如果你看到 [OK] 标志，接下来运行:
echo   gradlew.bat assembleDebug
echo.
echo 如果下载失败，请手动下载 gradle-wrapper.jar 放到 android\gradle\wrapper\ 目录
echo 或运行: npx react-native init LookGmTemp 然后复制其 android\gradle\wrapper\gradle-wrapper.jar
echo.
pause
