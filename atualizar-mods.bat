@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "JAVA_CMD="
where java >nul 2>&1
if %errorlevel% equ 0 (
    set "JAVA_CMD=java"
    goto :run_packwiz
)

for /d %%D in ("%~dp0..\..\meta\java_versions\zulu21*" "%APPDATA%\ModrinthApp\meta\java_versions\zulu21*") do (
    if exist "%%~fD\bin\java.exe" (
        set "JAVA_CMD=%%~fD\bin\java.exe"
        goto :run_packwiz
    )
)

for /d %%D in ("%~dp0..\..\meta\java_versions\*" "%APPDATA%\ModrinthApp\meta\java_versions\*") do (
    if exist "%%~fD\bin\java.exe" (
        set "JAVA_CMD=%%~fD\bin\java.exe"
        goto :run_packwiz
    )
)

echo [Create+] ERRO: Java nao encontrado no PATH nem no Modrinth App.
exit /b 1

:run_packwiz
echo [Create+] Verificando atualizacoes do modpack antes de abrir o Minecraft...
"%JAVA_CMD%" -jar "%~dp0packwiz-installer-bootstrap.jar" https://raw.githubusercontent.com/charles-gabriel-cc/create-plus-server/main/pack.toml
exit /b %errorlevel%
