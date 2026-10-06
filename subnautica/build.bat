@echo off
rem Builds the Subnautica side and copies it into the game's mod folder.
rem Full output is saved to build-log.txt.
cd /d "%~dp0"
echo Building Minecraft Link (Subnautica side)... the first run downloads a small package.
dotnet build -c Release > build-log.txt 2>&1
if %ERRORLEVEL%==0 (
	echo BUILD OK>> build-log.txt
	echo.
	echo Build succeeded. The mod was copied into Subnautica\BepInEx\plugins\MinecraftLink
) else (
	echo BUILD FAILED>> build-log.txt
	echo.
	echo Build failed. See build-log.txt
)
pause
