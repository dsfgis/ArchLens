param([string]$JdkHome = $env:ARCHLENS_JAVA_HOME, [switch]$PgOnly)
$ErrorActionPreference = 'Stop'
$serviceRoot = Split-Path $PSScriptRoot -Parent
$names = @('JAVA_HOME','ARCHLENS_PG_URL','ARCHLENS_PG_USER','ARCHLENS_PG_PASSWORD','ARCHLENS_NEO4J_URI','ARCHLENS_NEO4J_USER','ARCHLENS_NEO4J_PASSWORD','ARCHLENS_NEO4J_DATABASE','ARCHLENS_STORAGE_IT')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $serviceRoot
try {
    $env:JAVA_HOME = $JdkHome
    $config = Get-Content -LiteralPath '.local/storage.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($name in $names) { if ($name -like 'ARCHLENS_*' -and $name -notlike '*PASSWORD' -and $name -ne 'ARCHLENS_STORAGE_IT' -and -not [Environment]::GetEnvironmentVariable($name,'Process')) { [Environment]::SetEnvironmentVariable($name,$config.$name,'Process') } }
    $secrets = Import-Clixml -LiteralPath '.local/storage.credentials.xml'
    foreach ($key in @('PG','NEO4J')) { $name='ARCHLENS_'+$key+'_PASSWORD'; if(-not [Environment]::GetEnvironmentVariable($name,'Process')) { [Environment]::SetEnvironmentVariable($name,([PSCredential]::new('storage',$secrets[$key])).GetNetworkCredential().Password,'Process') } }
    $env:ARCHLENS_STORAGE_IT='true'
    # 网页非列调查没有 Neo4j 投影；可独立验证 PG 闭环，完整双库回归仍保留默认入口。
    $testArgs = @()
    if ($PgOnly) { $testArgs += '-Dtest=!Neo4jProjectionTest,!StorageIntegrationTest' }
    & mvn.cmd --batch-mode --no-transfer-progress '-Dmaven.repo.local=.local/m2' '-DforkCount=0' @testArgs verify
    if($LASTEXITCODE -ne 0) { throw "Web verification failed: $LASTEXITCODE" }
} finally { Pop-Location; foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') } }
