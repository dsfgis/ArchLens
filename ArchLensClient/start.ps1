param([string]$JdkHome = $env:ARCHLENS_JAVA_HOME, [switch]$Offline)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
    throw 'Provide a JDK 21 path with -JdkHome or ARCHLENS_JAVA_HOME.'
}
$previousJava = $env:ARCHLENS_JAVA_HOME
$previousKey = $env:DEEPSEEK_API_KEY
$storageNames = @('ARCHLENS_PG_URL','ARCHLENS_PG_USER','ARCHLENS_PG_PASSWORD','ARCHLENS_NEO4J_URI','ARCHLENS_NEO4J_USER','ARCHLENS_NEO4J_PASSWORD','ARCHLENS_NEO4J_DATABASE')
$previousStorage = @{}
foreach ($name in $storageNames) { $previousStorage[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:ARCHLENS_JAVA_HOME = $JdkHome
    # 只向 Node/Java 子进程注入 ArchLens 自身存储配置，不发送到页面。
    $serviceRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'ArchLensService'
    $configPath = Join-Path $serviceRoot '.local/storage.json'
    if (Test-Path -LiteralPath $configPath) {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($name in $storageNames) {
            if ($name -notlike '*PASSWORD' -and -not [Environment]::GetEnvironmentVariable($name,'Process')) { [Environment]::SetEnvironmentVariable($name,$config.$name,'Process') }
        }
    }
    $secretsPath = Join-Path $serviceRoot '.local/storage.credentials.xml'
    if (Test-Path -LiteralPath $secretsPath) {
        $secrets = Import-Clixml -LiteralPath $secretsPath
        foreach ($key in @('PG','NEO4J')) {
            $name = 'ARCHLENS_' + $key + '_PASSWORD'
            if (-not [Environment]::GetEnvironmentVariable($name,'Process')) { [Environment]::SetEnvironmentVariable($name,([PSCredential]::new('storage',$secrets[$key])).GetNetworkCredential().Password,'Process') }
        }
    }
    if ($Offline) { $env:DEEPSEEK_API_KEY = $null }
    elseif (-not $env:DEEPSEEK_API_KEY) { $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User') }
    if (-not $Offline -and -not $env:DEEPSEEK_API_KEY) {
        $secureKey = Read-Host 'DeepSeek API key (hidden, kept only for this process)' -AsSecureString
        $env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $secureKey.Dispose()
    }
    & node (Join-Path $PSScriptRoot 'server.mjs')
} finally {
    $env:ARCHLENS_JAVA_HOME = $previousJava
    $env:DEEPSEEK_API_KEY = $previousKey
    foreach ($name in $storageNames) { [Environment]::SetEnvironmentVariable($name,$previousStorage[$name],'Process') }
}
