param(
    [Parameter(Mandatory=$true)][ValidateSet('storage-check','storage-init','storage-verify','investigate-store','agent-investigate-store','agent-resume-store','run-export','run-status','run-cancel','run-expire','projection-retry')][string]$Command,
    [string[]]$CommandArgs = @(),
    [string]$JdkHome = $env:ARCHLENS_JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$serviceRoot = Split-Path $PSScriptRoot -Parent
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) { throw 'Set ARCHLENS_JAVA_HOME to JDK 21.' }
$names = @('ARCHLENS_PG_URL','ARCHLENS_PG_USER','ARCHLENS_PG_PASSWORD','ARCHLENS_NEO4J_URI','ARCHLENS_NEO4J_USER','ARCHLENS_NEO4J_PASSWORD','ARCHLENS_NEO4J_DATABASE','ARCHLENS_STORAGE_IT')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $configPath = Join-Path $serviceRoot '.local/storage.json'
    $secretsPath = Join-Path $serviceRoot '.local/storage.credentials.xml'
    if (Test-Path -LiteralPath $configPath) {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        foreach ($name in $names) {
            if ($name -notlike '*PASSWORD' -and -not [Environment]::GetEnvironmentVariable($name,'Process')) {
                [Environment]::SetEnvironmentVariable($name,$config.$name,'Process')
            }
        }
    }
    if (Test-Path -LiteralPath $secretsPath) {
        $secrets = Import-Clixml -LiteralPath $secretsPath
        foreach ($key in @('PG','NEO4J')) {
            $name = 'ARCHLENS_' + $key + '_PASSWORD'
            if (-not [Environment]::GetEnvironmentVariable($name,'Process')) {
                $credential = [PSCredential]::new('storage',$secrets[$key])
                [Environment]::SetEnvironmentVariable($name,$credential.GetNetworkCredential().Password,'Process')
            }
        }
    }
    if ($Command -eq 'storage-verify') {
        $env:ARCHLENS_STORAGE_IT = 'true'
        & (Join-Path $PSScriptRoot 'build.ps1') -JdkHome $JdkHome
    } else {
        & (Join-Path $JdkHome 'bin/java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -jar (Join-Path $serviceRoot 'target/archlens-0.1.0-SNAPSHOT-cli.jar') $Command @CommandArgs
        if ($LASTEXITCODE -ne 0) { throw "Storage command returned $LASTEXITCODE" }
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
