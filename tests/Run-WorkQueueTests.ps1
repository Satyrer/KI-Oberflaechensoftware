param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [string]$AuditDirectory
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AuditDirectory)) { $AuditDirectory = Join-Path $projectDirectory 'target\queue-tests' }
New-Item -ItemType Directory -Force -Path $AuditDirectory | Out-Null
$desktop = Join-Path $projectDirectory 'target\desktop'
$classpath = "$desktop\ki-oberflaeche.jar;$desktop\lib\*;$AuditDirectory"
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $AuditDirectory (Join-Path $PSScriptRoot 'AgentFixture.java') (Join-Path $PSScriptRoot 'AgentUiRegression.java') (Join-Path $PSScriptRoot 'WorkQueueRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Queue-Testquellen konnten nicht kompiliert werden.' }
& (Join-Path $JavaDirectory 'bin\java.exe') "-Dki.modelPreferences=$AuditDirectory\isolated-preferences.properties" -cp $classpath WorkQueueRegression $AuditDirectory
if ($LASTEXITCODE -ne 0) { throw 'Queue-Regression fehlgeschlagen.' }
