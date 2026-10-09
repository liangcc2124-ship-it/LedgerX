[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousTestDataRoot = $env:LEDGERX_TEST_DATA_DIR
$project = [xml](Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'pom.xml'))
$artifact = Join-Path $projectRoot "target/p7-003/$($project.project.artifactId)-$($project.project.version).jar"
$javaExecutable = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else {
    (Get-Command java -ErrorAction Stop).Source
}

if (-not (Test-Path -LiteralPath $artifact)) {
    throw 'The local-web application has not been built. Run scripts/build-web.ps1 once before starting it.'
}
$javaDetails = (& $javaExecutable -version 2>&1 | Out-String)
$javaVersion = [regex]::Match($javaDetails, 'version "(?<major>[0-9]+)(?:\.(?<minor>[0-9]+))?').Groups
$javaMajor = if ($javaVersion['major'].Value -eq '1') { $javaVersion['minor'].Value } else { $javaVersion['major'].Value }
if ($javaMajor -ne '11') {
    throw "Java 11 is required to run LedgerX Local Web; detected: $($javaDetails.Trim())."
}

Push-Location $projectRoot
try {
    Remove-Item Env:\LEDGERX_TEST_DATA_DIR -ErrorAction SilentlyContinue
    & $javaExecutable -jar $artifact
    $javaExitCode = $LASTEXITCODE
    if ($javaExitCode -eq 1) {
        Write-Host 'Java returned status 1. This is common after Ctrl+C on Windows; if the stop was unexpected, review the Java output above.' -ForegroundColor Yellow
    } elseif ($javaExitCode -ne 0) {
        throw "LedgerX stopped with exit code $javaExitCode."
    }
} finally {
    if ($null -eq $previousTestDataRoot) {
        Remove-Item Env:\LEDGERX_TEST_DATA_DIR -ErrorAction SilentlyContinue
    } else {
        $env:LEDGERX_TEST_DATA_DIR = $previousTestDataRoot
    }
    Pop-Location
}
