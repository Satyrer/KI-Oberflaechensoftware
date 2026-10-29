param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$desktopDirectory = Join-Path $projectDirectory 'target\desktop'
$javaExecutable = Join-Path $JavaDirectory 'bin\javaw.exe'
$applicationJar = Join-Path $desktopDirectory 'ki-oberflaeche.jar'
foreach ($required in @($javaExecutable,$applicationJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Startdatei fehlt: $required. Zuerst Build-KI.ps1 ausfuehren bzw. JavaDirectory pruefen." }
}
foreach ($module in @('base','graphics','controls','fxml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory "lib\javafx-$module-21-win.jar") -PathType Leaf)) { throw 'Desktop-Laufzeit unvollstaendig. Build-KI.ps1 erneut ausfuehren.' }
}
if ($CheckOnly) {
    Write-Output "Startbereit: $applicationJar"
    Write-Output "Arbeitsverzeichnis: $projectDirectory"
    exit 0
}
$classPath = "$applicationJar;$desktopDirectory\lib\*"
# This is the interactive application, not a background helper.
Start-Process -FilePath $javaExecutable -ArgumentList @('-cp',('"' + $classPath + '"'),'kioberflaeche.Main') -WorkingDirectory $projectDirectory -WindowStyle Normal
