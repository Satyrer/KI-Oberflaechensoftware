param(
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-21',
    [string]$MavenDirectory = 'C:\Program Files\apache-maven-3.9.6'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$javaExecutable = Join-Path $JavaDirectory 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'Java 21 oder neuer fehlt. JavaDirectory auf eine vorhandene JDK-Installation setzen.' }
Push-Location -LiteralPath $projectDirectory
try {
    & $javaExecutable "-Dmaven.home=$MavenDirectory" "-Dmaven.multiModuleProjectDirectory=$projectDirectory" -cp "$MavenDirectory\boot\*" "-Dclassworlds.conf=$MavenDirectory\bin\m2.conf" org.codehaus.plexus.classworlds.launcher.Launcher -o package
    if ($LASTEXITCODE -ne 0) { throw 'Maven-Paket konnte nicht erstellt werden.' }
    $desktopDirectory = Join-Path $projectDirectory 'target\desktop'
    $libraryDirectory = Join-Path $desktopDirectory 'lib'
    New-Item -ItemType Directory -Force -Path $libraryDirectory | Out-Null
    foreach ($module in @('base','graphics','controls','fxml')) {
        $source = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx\javafx-$module\21\javafx-$module-21-win.jar"
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "JavaFX-Laufzeit fehlt: $module (Version 21, Windows)." }
        Copy-Item -LiteralPath $source -Destination $libraryDirectory -Force
    }
    Copy-Item -LiteralPath (Join-Path $projectDirectory 'target\KI-Oberflaechensoftware-1.0-SNAPSHOT.jar') -Destination (Join-Path $desktopDirectory 'ki-oberflaeche.jar') -Force
    Write-Output "Desktop-Paket erstellt: $desktopDirectory"
} finally { Pop-Location }
