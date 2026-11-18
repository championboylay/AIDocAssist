# Builds the self-contained Windows bundle: target\bundle\AIDocAssist\ (app + its own Java runtime)
# and target\bundle\AIDocAssist-<version>-windows.zip. jpackage builds for the OS it runs on only;
# run this on Linux (with pwsh) for a Linux bundle.
#
#   powershell -ExecutionPolicy Bypass -File packaging\bundle.ps1 [-SkipTests]
#
# Needs a JDK 21 (with jpackage) in JAVA_HOME. Nothing else: Maven comes from mvnw.
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path "$jdk\bin\jpackage*")) { throw "Set JAVA_HOME to a JDK 21 that has jpackage (now: '$jdk')." }
$ver = Select-String -Path "$jdk\release" -Pattern '^JAVA_VERSION=' | Select-Object -First 1
if ("$ver" -notmatch '"21') { throw "JAVA_HOME must be JDK 21, found: $ver" }

Push-Location $root
try {
    $version = ([xml](Get-Content pom.xml)).project.version
    $mvnArgs = @('-q', 'clean', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

    # jpackage copies everything in --input, so give it the jar alone.
    $staging = 'target\jpackage-input'
    New-Item -ItemType Directory -Force $staging | Out-Null
    Copy-Item target\aidocassist.jar $staging

    # Default modules = every JDK module with an API (java.desktop for PDFBox/Tabula, java.sql, ...).
    # ponytail: the full default runtime (~150 MB unzipped); a hand-picked --add-modules list could halve it, at the risk of a missing module at runtime.
    & "$jdk\bin\jpackage" --type app-image --name AIDocAssist --app-version $version `
        --input $staging --main-jar aidocassist.jar --dest target\bundle --win-console `
        --description "AI Doc Assist - cited answers from SIP documents"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

    $app = 'target\bundle\AIDocAssist'
    Copy-Item packaging\start.bat, packaging\application.properties, packaging\README.md $app

    $zip = "target\bundle\AIDocAssist-$version-windows.zip"
    # tar (built into Windows 10+) reads files another process has open; Compress-Archive refuses them.
    Remove-Item $zip -ErrorAction SilentlyContinue
    tar -a -cf $zip -C target\bundle AIDocAssist
    if ($LASTEXITCODE -ne 0) { throw "zip failed." }
    Write-Host "Bundle: $app"
    Write-Host "Zip:    $zip ($([math]::Round((Get-Item $zip).Length / 1MB)) MB)"
} finally {
    Pop-Location
}
