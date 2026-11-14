@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [Create+] Sincronizando metadados do Packwiz (Modrinth App ^<^> Packwiz)...
python scripts\sync_packwiz.py
if %errorlevel% neq 0 (
    echo [ERRO] Falha ao sincronizar o Packwiz. Verifique o erro acima.
    pause
    exit /b %errorlevel%
)
echo [Create+] Verificando alteracoes em mods, resourcepacks, shaderpacks, config e Packwiz...
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
echo [Create+] Enviado com sucesso! O GitHub Actions ja esta atualizando o servidor da EnxadaHost e o .mrpack.
pause
