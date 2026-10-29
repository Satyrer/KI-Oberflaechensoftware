param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [string]$AuditDirectory
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AuditDirectory)) { $AuditDirectory = Join-Path $projectDirectory 'target\document-source-isolation-tests' }
$AuditDirectory = [IO.Path]::GetFullPath($AuditDirectory)
$classesDirectory = Join-Path $AuditDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$libraryDirectory = Join-Path $projectDirectory 'target\desktop\lib'
if (-not (Test-Path -LiteralPath $libraryDirectory -PathType Container)) { throw 'JavaFX-Bibliotheken fehlen; zuerst Build-KI.ps1 ausfuehren.' }
$compileClasspath = "$libraryDirectory\*"
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sourceFiles += Join-Path $PSScriptRoot 'DocumentSourceIsolationRegression.java'
$sourceFiles += Join-Path $PSScriptRoot 'StoryUiRegression.java'
& (Join-Path $JavaDirectory 'bin\javac.exe') -encoding UTF-8 -cp $compileClasspath -d $classesDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Isolierter Testbuild fehlgeschlagen.' }
$testClasspath = "$classesDirectory;$projectDirectory\src\main\resources;$libraryDirectory\*"
foreach ($test in @('DocumentSourceIsolationRegression', 'StoryUiRegression')) {
    $log = Join-Path $AuditDirectory "$test.log"
    & (Join-Path $JavaDirectory 'bin\java.exe') "-Dki.modelPreferences=$AuditDirectory\isolated-model-preferences.properties" -cp $testClasspath $test $AuditDirectory *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log; throw "Regression fehlgeschlagen: $test" }
    Get-Content -LiteralPath $log
}
