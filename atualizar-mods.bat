@echo off
chcp 65001 >nul
setlocal

set "INST_DIR=%~dp0"
if "%INST_DIR:~-1%"=="\" set "INST_DIR=%INST_DIR:~0,-1%"
set "BAT_SELF=%~f0"

set "HAS_INST=0"
if exist "%INST_DIR%\mods\" set "HAS_INST=1"
if exist "%INST_DIR%\pack.toml" set "HAS_INST=1"

if "%HAS_INST%"=="0" (
    set "FOUND_DIR="
    for /f "usebackq delims=" %%D in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:BAT_SELF,[Text.Encoding]::UTF8); & ([ScriptBlock]::Create($t.Substring($t.LastIndexOf('#__PS_'+'START__')))) 'find'"`) do (
        set "FOUND_DIR=%%D"
    )
    if not defined FOUND_DIR goto :no_instance
    goto :found_instance
)
goto :in_instance

:no_instance
echo [Create+] ERRO: Nenhuma instancia Create+ encontrada no Modrinth App.
echo [Create+] Importe o Create+.mrpack no Modrinth App ou coloque este arquivo na pasta da instancia.
pause
exit /b 1

:found_instance
set "INST_DIR=%FOUND_DIR%"
if /i not "%~f0"=="%INST_DIR%\atualizar-mods.bat" (
    copy /y "%~f0" "%INST_DIR%\atualizar-mods.bat" >nul
    echo [Create+] Script copiado para "%INST_DIR%\atualizar-mods.bat".
)

:in_instance
cd /d "%INST_DIR%"
if errorlevel 1 (
    echo [Create+] ERRO: Nao foi possivel acessar a pasta "%INST_DIR%".
    exit /b 1
)

if not exist "%INST_DIR%\.prelaunch_ok" (
    set "TARGET_INST_DIR=%INST_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:BAT_SELF,[Text.Encoding]::UTF8); & ([ScriptBlock]::Create($t.Substring($t.LastIndexOf('#__PS_'+'START__')))) 'set_prelaunch'"
)

if not exist "%INST_DIR%\packwiz-installer-bootstrap.jar" (
    echo [Create+] Baixando packwiz-installer-bootstrap.jar...
    curl.exe -fL -sS -o "%INST_DIR%\packwiz-installer-bootstrap.jar" https://raw.githubusercontent.com/charles-gabriel-cc/create-plus-server/main/packwiz-installer-bootstrap.jar
    if errorlevel 1 (
        echo [Create+] ERRO: Falha ao baixar packwiz-installer-bootstrap.jar.
        exit /b 1
    )
)

set "JAVA_CMD="
where java >nul 2>&1
if %errorlevel% equ 0 (
    set "JAVA_CMD=java"
    goto :run_packwiz
)

for /d %%D in ("%INST_DIR%\..\..\meta\java_versions\zulu21*" "%APPDATA%\ModrinthApp\meta\java_versions\zulu21*") do (
    if exist "%%~fD\bin\java.exe" (
        set "JAVA_CMD=%%~fD\bin\java.exe"
        goto :run_packwiz
    )
)

for /d %%D in ("%INST_DIR%\..\..\meta\java_versions\*" "%APPDATA%\ModrinthApp\meta\java_versions\*") do (
    if exist "%%~fD\bin\java.exe" (
        set "JAVA_CMD=%%~fD\bin\java.exe"
        goto :run_packwiz
    )
)

echo [Create+] ERRO: Java nao encontrado no PATH nem no Modrinth App.
exit /b 1

:run_packwiz
echo [Create+] Verificando atualizacoes do modpack antes de abrir o Minecraft...
"%JAVA_CMD%" -jar "%INST_DIR%\packwiz-installer-bootstrap.jar" https://raw.githubusercontent.com/charles-gabriel-cc/create-plus-server/main/pack.toml
exit /b %errorlevel%

#__PS_START__
param([string]$Action)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$appData = [Environment]::GetFolderPath('ApplicationData')
$dbPath = Join-Path $appData 'ModrinthApp\app.db'
if (-not (Test-Path -LiteralPath $dbPath)) { exit 0 }

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class WinSqlite3Helper {
    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_open_v2(byte[] filename, out IntPtr db, int flags, IntPtr zVfs);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_close(IntPtr db);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_busy_timeout(IntPtr db, int ms);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_prepare_v2(IntPtr db, byte[] sql, int nByte, out IntPtr stmt, IntPtr pzTail);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_bind_text(IntPtr stmt, int index, byte[] val, int nByte, IntPtr destructor);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_step(IntPtr stmt);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr sqlite3_column_text(IntPtr stmt, int iCol);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_column_bytes(IntPtr stmt, int iCol);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_column_type(IntPtr stmt, int iCol);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_finalize(IntPtr stmt);

    [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int sqlite3_changes(IntPtr db);

    public static byte[] Utf8Z(string s) {
        byte[] b = Encoding.UTF8.GetBytes(s);
        byte[] r = new byte[b.Length + 1];
        Array.Copy(b, r, b.Length);
        return r;
    }

    public static string ColStr(IntPtr stmt, int col) {
        if (sqlite3_column_type(stmt, col) == 5) return null;
        IntPtr p = sqlite3_column_text(stmt, col);
        int len = sqlite3_column_bytes(stmt, col);
        if (p == IntPtr.Zero || len <= 0) return "";
        byte[] b = new byte[len];
        Marshal.Copy(p, b, 0, len);
        return Encoding.UTF8.GetString(b);
    }
}
'@

function Open-Db([int]$Flags) {
    $db = [IntPtr]::Zero
    $rc = [WinSqlite3Helper]::sqlite3_open_v2([WinSqlite3Helper]::Utf8Z($dbPath), [ref]$db, $Flags, [IntPtr]::Zero)
    if ($rc -ne 0 -or $db -eq [IntPtr]::Zero) {
        if ($db -ne [IntPtr]::Zero) { [void][WinSqlite3Helper]::sqlite3_close($db) }
        return [IntPtr]::Zero
    }
    [void][WinSqlite3Helper]::sqlite3_busy_timeout($db, 5000)
    return $db
}

function Bind-Text([IntPtr]$Stmt, [int]$Index, [string]$Value) {
    $raw = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $buf = [WinSqlite3Helper]::Utf8Z($Value)
    return [WinSqlite3Helper]::sqlite3_bind_text($Stmt, $Index, $buf, $raw.Length, [IntPtr](-1))
}

$modrinthDefaultBase = Join-Path $appData 'ModrinthApp'

if ($Action -eq 'find') {
    $db = Open-Db 1
    if ($db -eq [IntPtr]::Zero) { exit 0 }
    try {
        $sql = @"
SELECT COALESCE(NULLIF(s.custom_dir, ''), ?1), i.path
FROM instances i
LEFT JOIN settings s ON s.id = 0
WHERE i.name = 'Create+' OR i.name LIKE 'Create+%' OR i.path LIKE 'Create+%'
ORDER BY i.modified DESC, i.created DESC;
"@
        $stmt = [IntPtr]::Zero
        if ([WinSqlite3Helper]::sqlite3_prepare_v2($db, [WinSqlite3Helper]::Utf8Z($sql), -1, [ref]$stmt, [IntPtr]::Zero) -eq 0) {
            try {
                [void](Bind-Text $stmt 1 $modrinthDefaultBase)
                while ([WinSqlite3Helper]::sqlite3_step($stmt) -eq 100) {
                    $baseDir = [WinSqlite3Helper]::ColStr($stmt, 0)
                    $instPath = [WinSqlite3Helper]::ColStr($stmt, 1)
                    if ([string]::IsNullOrWhiteSpace($instPath)) { continue }
                    $candidate = if ([System.IO.Path]::IsPathRooted($instPath)) {
                        $instPath
                    } else {
                        Join-Path (Join-Path $baseDir 'profiles') $instPath
                    }
                    if (Test-Path -LiteralPath $candidate -PathType Container) {
                        Write-Output $candidate
                        break
                    }
                }
            } finally {
                if ($stmt -ne [IntPtr]::Zero) { [void][WinSqlite3Helper]::sqlite3_finalize($stmt) }
            }
        }
    } finally {
        [void][WinSqlite3Helper]::sqlite3_close($db)
    }
    exit 0
}

if ($Action -eq 'set_prelaunch') {
    $instDir = $env:TARGET_INST_DIR
    if ([string]::IsNullOrWhiteSpace($instDir)) { exit 1 }
    $folderName = Split-Path -Leaf $instDir
    $markerPath = Join-Path $instDir '.prelaunch_ok'
    $hookCmd = 'cmd /c atualizar-mods.bat'

    $db = Open-Db 2
    if ($db -eq [IntPtr]::Zero) { exit 1 }
    try {
        $checkSql = @"
SELECT json_extract(o.overrides, '$.hooks.pre_launch')
FROM instances i
LEFT JOIN instance_launch_overrides o ON o.instance_id = i.id
WHERE i.path = ?1;
"@
        $stmt = [IntPtr]::Zero
        $foundInstance = $false
        $alreadySet = $false
        if ([WinSqlite3Helper]::sqlite3_prepare_v2($db, [WinSqlite3Helper]::Utf8Z($checkSql), -1, [ref]$stmt, [IntPtr]::Zero) -eq 0) {
            try {
                [void](Bind-Text $stmt 1 $folderName)
                if ([WinSqlite3Helper]::sqlite3_step($stmt) -eq 100) {
                    $foundInstance = $true
                    $curHook = [WinSqlite3Helper]::ColStr($stmt, 0)
                    if ($curHook -eq $hookCmd) {
                        $alreadySet = $true
                    }
                }
            } finally {
                if ($stmt -ne [IntPtr]::Zero) { [void][WinSqlite3Helper]::sqlite3_finalize($stmt) }
            }
        }

        if (-not $foundInstance) { exit 2 }
        if ($alreadySet) {
            [System.IO.File]::WriteAllText($markerPath, "ok`n", [System.Text.Encoding]::UTF8)
            exit 0
        }

        $updSql = @"
INSERT INTO instance_launch_overrides (instance_id, overrides)
SELECT id, jsonb('{"hooks":{"pre_launch":"cmd /c atualizar-mods.bat","wrapper":null,"post_exit":null}}')
FROM instances WHERE path = ?1
ON CONFLICT(instance_id) DO UPDATE SET
  overrides = CASE
    WHEN json_type(overrides, '$.hooks') = 'object' THEN
      jsonb_set(overrides, '$.hooks.pre_launch', 'cmd /c atualizar-mods.bat')
    ELSE
      jsonb_set(COALESCE(overrides, jsonb('{}')), '$.hooks', json('{"pre_launch":"cmd /c atualizar-mods.bat","wrapper":null,"post_exit":null}'))
  END;
"@
        $updStmt = [IntPtr]::Zero
        if ([WinSqlite3Helper]::sqlite3_prepare_v2($db, [WinSqlite3Helper]::Utf8Z($updSql), -1, [ref]$updStmt, [IntPtr]::Zero) -eq 0) {
            try {
                [void](Bind-Text $updStmt 1 $folderName)
                $stepRc = [WinSqlite3Helper]::sqlite3_step($updStmt)
                if ($stepRc -eq 101 -and [WinSqlite3Helper]::sqlite3_changes($db) -gt 0) {
                    [System.IO.File]::WriteAllText($markerPath, "ok`n", [System.Text.Encoding]::UTF8)
                    Write-Host "[Create+] Pre-launch configurado automaticamente no Modrinth App!"
                    exit 0
                }
            } finally {
                if ($updStmt -ne [IntPtr]::Zero) { [void][WinSqlite3Helper]::sqlite3_finalize($updStmt) }
            }
        }
        exit 1
    } finally {
        [void][WinSqlite3Helper]::sqlite3_close($db)
    }
}
