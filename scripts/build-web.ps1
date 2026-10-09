[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchain = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'toolchain.json') | ConvertFrom-Json
$frontendRoot = Join-Path $projectRoot 'frontend'
$targetRoot = Join-Path $projectRoot 'target/p7-003'

$javaExecutable = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else {
    (Get-Command java -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java executable is missing: $javaExecutable" }
$javaDetails = (& $javaExecutable -version 2>&1 | Out-String)
$javaMajor = [regex]::Match($javaDetails, 'version "(?<major>[0-9]+)').Groups['major'].Value
if ($javaMajor -ne [string]$toolchain.javaMajor) {
    throw "Java $($toolchain.javaMajor) is required; detected: $($javaDetails.Trim())."
}

$cacheRoot = Join-Path $projectRoot '.maven-home/.m2'
$previousMavenUserHome = $env:MAVEN_USER_HOME
$env:MAVEN_USER_HOME = $cacheRoot
$mavenRepository = Join-Path $cacheRoot 'repository'
$mavenArguments = @("-Dmaven.repo.local=$mavenRepository")

try {
    Push-Location $projectRoot
    try {
        Push-Location $frontendRoot
        try {
            if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules'))) {
                throw 'Frontend dependencies are missing. Run npm ci in the frontend directory first.'
            }
            npm run build
            if ($LASTEXITCODE -ne 0) { throw "Vue build failed with exit code $LASTEXITCODE." }
            node (Join-Path $PSScriptRoot 'write-web-manifest.mjs') (Join-Path $frontendRoot 'dist')
            if ($LASTEXITCODE -ne 0) { throw "Web resource manifest generation failed with exit code $LASTEXITCODE." }
            $activeClient = @(Get-ChildItem -LiteralPath (Join-Path $frontendRoot 'dist/assets') -Filter '*.js' -File)
            $activeClientCode = ($activeClient | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }) -join "`n"
            if ($activeClientCode -match '(?i)window\.desktop|LEDGERX_SESSION_TOKEN|authMode.{0,24}legacy|authorization.{0,16}bearer') {
                throw 'Built browser assets still contain an Electron bridge or legacy Bearer session path.'
            }
        } finally {
            Pop-Location
        }

        $mavenArguments += @('-q', "-Dledgerx.build.directory=$targetRoot", 'clean', 'verify')
        & .\mvnw.cmd @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE." }

        $projectModel = [xml](Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'pom.xml'))
        $jarPath = Join-Path $targetRoot "$($projectModel.project.artifactId)-$($projectModel.project.version).jar"
        if (-not $jarPath -or -not (Test-Path -LiteralPath $jarPath)) { throw 'Packaged Java web application was not produced.' }
        $runtimeJars = @(Get-ChildItem -LiteralPath (Join-Path $targetRoot 'runtime/lib') -Filter '*.jar' -File -ErrorAction SilentlyContinue)
        if ($runtimeJars.Count -eq 0) {
            throw 'Runtime dependency directory is empty; the application would not run independently.'
        }
        $jarEntries = & jar tf $jarPath
        if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains 'web/index.html' -or $jarEntries -notcontains 'web/manifest.properties') {
            throw 'Java artifact does not contain the same-origin Vue application and allowlist manifest.'
        }

        Write-Host "Web build complete: $jarPath"
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousMavenUserHome) {
        Remove-Item Env:\MAVEN_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:MAVEN_USER_HOME = $previousMavenUserHome
    }
}
