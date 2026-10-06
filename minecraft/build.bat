@echo off
rem Builds the mod and saves the full output to build-log.txt.
rem The finished mod jar ends up in build\libs\
cd /d "%~dp0"
echo Building Subnautica Link (Minecraft side)... the first run downloads Minecraft and can take 5-15 minutes.
call gradlew.bat build --no-daemon --console=plain > build-log.txt 2>&1
if %ERRORLEVEL%==0 (
	echo BUILD OK>> build-log.txt
	echo.
	echo Build succeeded. Your mod is in build\libs\
) else (
	echo BUILD FAILED>> build-log.txt
	echo.
	echo Build failed. See build-log.txt
)
pause
