@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [Create+] Verificando alteracoes em mods, resourcepacks, shaderpacks e config...
git add .
git diff --cached --quiet
if %errorlevel% equ 0 (
    echo Nenhuma alteracao encontrada para enviar.
    pause
    exit /b 0
)
echo [Create+] Criando commit e enviando para o GitHub (deploy automatico na EnxadaHost)...
git commit -m "chore: update modpack files (%date% %time%)"
git push origin main
echo.
echo [Create+] Enviado com sucesso! O GitHub Actions ja esta atualizando o servidor da EnxadaHost.
pause
