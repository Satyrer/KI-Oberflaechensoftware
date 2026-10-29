param(
    [string]$BaseUrl = 'http://192.168.178.41:5678',
    [string]$AuditDirectory = 'C:\KI Arbeitsbereich\Aenderungen-2026-10-16-DeepSeek-Modellwahl',
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [switch]$RequireAllInstalled
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$desktopDirectory = Join-Path $projectDirectory 'target\desktop'
$testClasspath = "$desktopDirectory\ki-oberflaeche.jar;$desktopDirectory\lib\*;$AuditDirectory"
New-Item -ItemType Directory -Force -Path $AuditDirectory | Out-Null
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $testClasspath -d $AuditDirectory (Join-Path $PSScriptRoot 'LiveModelCatalogCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Live-Katalogtest konnte nicht kompiliert werden.' }
$testArguments = @('-cp',$testClasspath,'LiveModelCatalogCheck',$BaseUrl,$AuditDirectory)
$logName = 'java-live-catalog.log'
if ($RequireAllInstalled) { $testArguments += '--all-installed'; $logName = 'java-live-catalog-all-installed.log' }
& (Join-Path $JavaDirectory 'bin\java.exe') @testArguments *> (Join-Path $AuditDirectory $logName)
$result = $LASTEXITCODE
Get-Content -LiteralPath (Join-Path $AuditDirectory $logName)
if ($result -ne 0) { throw 'Live-Katalogtest fehlgeschlagen; keine Modellgenerierung wurde angefordert.' }
