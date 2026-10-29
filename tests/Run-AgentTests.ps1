param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [string]$AuditDirectory
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AuditDirectory)) { $AuditDirectory = Join-Path $projectDirectory 'target\agent-tests' }
New-Item -ItemType Directory -Force -Path $AuditDirectory | Out-Null
$desktopDirectory = Join-Path $projectDirectory 'target\desktop'
if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory 'ki-oberflaeche.jar'))) { throw 'Zuerst Build-KI.ps1 ausfuehren.' }
$testClasspath = "$desktopDirectory\ki-oberflaeche.jar;$desktopDirectory\lib\*;$AuditDirectory"
$sources = @('AgentFixture.java','AgentClientRegression.java','AgentUiRegression.java','AgentQueueRegression.java','AgentQueueUiRegression.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $testClasspath -d $AuditDirectory $sources
if ($LASTEXITCODE -ne 0) { throw 'Agententestquellen konnten nicht kompiliert werden.' }
foreach ($test in @('AgentClientRegression','AgentUiRegression','AgentQueueRegression','AgentQueueUiRegression')) {
    $log = Join-Path $AuditDirectory "$test.log"
    & (Join-Path $JavaDirectory 'bin\java.exe') "-Dki.modelPreferences=$AuditDirectory\isolated-agent-model-preferences.properties" -cp $testClasspath $test $AuditDirectory *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log; throw "Agentenregression fehlgeschlagen: $test" }
    Get-Content -LiteralPath $log
}
