param(
    [Parameter(Mandatory=$true)][string]$PgUrl,
    [Parameter(Mandatory=$true)][string]$PgUser,
    [Parameter(Mandatory=$true)][string]$Neo4jUri,
    [Parameter(Mandatory=$true)][string]$Neo4jUser,
    [string]$Neo4jDatabase = 'neo4j'
)
$ErrorActionPreference = 'Stop'
$localRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.local'
$configPath = Join-Path $localRoot 'storage.json'
$secretPath = Join-Path $localRoot 'storage.credentials.xml'
if ((Test-Path -LiteralPath $configPath) -or (Test-Path -LiteralPath $secretPath)) { throw 'Configuration exists. Archive it explicitly before configuring another storage instance.' }
$pgSecret = Read-Host 'PostgreSQL password' -AsSecureString
$neoSecret = Read-Host 'Neo4j password' -AsSecureString
New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
@{ PG=$pgSecret; NEO4J=$neoSecret } | Export-Clixml -LiteralPath $secretPath
@{
    ARCHLENS_PG_URL=$PgUrl; ARCHLENS_PG_USER=$PgUser;
    ARCHLENS_NEO4J_URI=$Neo4jUri; ARCHLENS_NEO4J_USER=$Neo4jUser; ARCHLENS_NEO4J_DATABASE=$Neo4jDatabase
} | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
Write-Output 'Stored backend configuration; Windows passwords are encrypted for this user on this machine.'
