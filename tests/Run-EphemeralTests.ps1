param([string]$JavaDirectory='C:\Program Files\Java\jdk-21',[string]$AuditDirectory)
$ErrorActionPreference='Stop'
$projectDirectory=Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AuditDirectory)) { $AuditDirectory=Join-Path $projectDirectory 'target\ephemeral-tests' }
New-Item -ItemType Directory -Force -Path $AuditDirectory | Out-Null
$desktopDirectory=Join-Path $projectDirectory 'target\desktop'
$testClasspath="$desktopDirectory\ki-oberflaeche.jar;$desktopDirectory\lib\*;$AuditDirectory"
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $testClasspath -d $AuditDirectory (Join-Path $PSScriptRoot 'EphemeralUiRegression.java') (Join-Path $PSScriptRoot 'EphemeralQueueRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Ephemeral-Testquellen konnten nicht kompiliert werden.' }
foreach ($testName in @('EphemeralUiRegression','EphemeralQueueRegression')) {
    $log=Join-Path $AuditDirectory "$testName.log"
    & (Join-Path $JavaDirectory 'bin\java.exe') "-Dki.modelPreferences=$AuditDirectory\isolated-model-preferences.properties" -cp $testClasspath $testName $AuditDirectory *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log; throw "$testName fehlgeschlagen." }
    Get-Content -LiteralPath $log
}
