param(
    [Parameter(Mandatory = $true)][ValidateSet('start','inspect','validate')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$ConfigurationFile,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [string]$JobId,
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$desktopDirectory = Join-Path $projectDirectory 'target\desktop'
if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory 'ki-oberflaeche.jar'))) { throw 'Zuerst Build-KI.ps1 ausfuehren.' }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Nachweisordner muss fuer jeden Aufruf neu sein.' }
$buildDirectory = Join-Path $projectDirectory 'target\agent-live-pilot'
New-Item -ItemType Directory -Force -Path $buildDirectory | Out-Null
New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null
$classpath = "$desktopDirectory\ki-oberflaeche.jar;$desktopDirectory\lib\*;$buildDirectory"
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $buildDirectory (Join-Path $PSScriptRoot 'AgentLivePilot.java')
if ($LASTEXITCODE -ne 0) { throw 'Pilotharness konnte nicht kompiliert werden.' }
$arguments = @("-Dki.modelPreferences=$buildDirectory\isolated-model-preferences.properties", '-cp', $classpath, 'AgentLivePilot', $Mode, $ConfigurationFile, $EvidenceDirectory)
if ($Mode -eq 'inspect') {
    if ($JobId -notmatch '^[0-9a-f]{32}$') { throw 'Inspect benoetigt eine gueltige Auftragskennung.' }
    $arguments += $JobId
} elseif ($JobId) { throw 'Auftragskennung ist nur bei Inspect erlaubt.' }
& (Join-Path $JavaDirectory 'bin\java.exe') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Pilotharness fehlgeschlagen; failure.json pruefen. Keinen Ersatzauftrag automatisch starten.' }
