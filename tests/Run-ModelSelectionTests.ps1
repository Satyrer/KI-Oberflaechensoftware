param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [string]$AuditDirectory
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AuditDirectory)) { $AuditDirectory = Join-Path $projectDirectory 'target\model-selection-tests' }
New-Item -ItemType Directory -Force -Path $AuditDirectory | Out-Null
$desktopDirectory = Join-Path $projectDirectory 'target\desktop'
if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory 'ki-oberflaeche.jar'))) { throw 'Zuerst Build-KI.ps1 ausfuehren.' }
$testClasspath = "$desktopDirectory\ki-oberflaeche.jar;$desktopDirectory\lib\*;$AuditDirectory"
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $testClasspath -d $AuditDirectory (Join-Path $PSScriptRoot 'ModelSelectionRegression.java') (Join-Path $PSScriptRoot 'StoryUiRegression.java') (Join-Path $PSScriptRoot 'RequestBudgetRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Testquellen konnten nicht kompiliert werden.' }
foreach ($test in @('ModelSelectionRegression','StoryUiRegression','RequestBudgetRegression')) {
    $log = Join-Path $AuditDirectory "$test.log"
    & (Join-Path $JavaDirectory 'bin\java.exe') "-Dki.modelPreferences=$AuditDirectory\isolated-model-preferences.properties" -cp $testClasspath $test $AuditDirectory *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log; throw "Regression fehlgeschlagen: $test" }
    Get-Content -LiteralPath $log
}
