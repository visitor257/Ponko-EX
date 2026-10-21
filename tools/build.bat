@echo off
REM Ponko-EX build helper - MUST stay pure ASCII.
REM
REM A separate project from the host. Keep this checkout at a PURE ASCII path:
REM AGP refuses to build when any parent directory has non-ASCII characters in
REM its name. If yours is under such a path, copy it to something like C:\PonkoEX
REM first - but a clean path is better than a sandbox copy.
REM
REM Why no Chinese characters in this file:
REM   cmd.exe parses .bat with the OEM codepage (GBK). A UTF-8 Chinese comment
REM   gets mangled, breaks if-blocks, and the script can loop forever.
REM
REM Prerequisite: the plugin SDK AAR must be published first, from the host project:
REM   cd <host build dir>
REM   tools\build-android.bat :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository
REM (Any time an AIDL file or a Parcelable changes, republish before building plugins.)
REM
REM Usage: tools\build.bat :plugins:echo:assembleRelease

setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 goto :novc

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=C:\Android"
set "ANDROID_SDK_ROOT=C:\Android"

cd /d "%~dp0.."
if not exist "settings.gradle.kts" goto :nosrc

REM The main branch carries docs only - no plugin modules. Say so clearly
REM instead of letting gradle fail with a confusing "project not found".
if not exist "plugins" goto :nomodules

call C:\gradle\gradle-9.7.1\bin\gradle.bat %* --console=plain --no-daemon
exit /b %errorlevel%

:novc
echo ERROR: vcvars64.bat not found or failed to load.
exit /b 1

:nosrc
echo ERROR: settings.gradle.kts not found next to tools\. Wrong folder?
exit /b 1

:nomodules
echo.
echo   No plugins\ directory here.
echo.
echo   This is the main branch: docs and build skeleton only, no plugin modules.
echo   Switch to a plugin line first, for example:
echo.
echo       git checkout Ponko-EX_test
echo.
exit /b 1
