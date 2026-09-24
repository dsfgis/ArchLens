param([string]$JdkHome = $env:ARCHLENS_JAVA_HOME, [switch]$Demo)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
    throw 'Set ARCHLENS_JAVA_HOME to a JDK 21 directory, or pass -JdkHome.'
}
$previousJava = $env:JAVA_HOME
$previousPath = $env:PATH
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = (Join-Path $JdkHome 'bin') + [IO.Path]::PathSeparator + $previousPath
    & mvn.cmd --batch-mode --no-transfer-progress '-Dmaven.repo.local=.local/m2' verify
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $LASTEXITCODE" }
    if ($Demo) {
        $reportPath = 'target/rename-report-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.json'
        & java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar analyze examples/column-rename/request.json $reportPath
        if ($LASTEXITCODE -ne 0) { throw "Demo failed: $LASTEXITCODE" }
    }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
}
