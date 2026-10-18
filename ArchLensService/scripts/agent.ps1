param(
    [Parameter(Mandatory=$true)][ValidateSet('agent-tools','agent-investigate','agent-resume','agent-investigate-store','agent-resume-store')][string]$Command,
    [string[]]$CommandArgs = @(),
    [string]$JdkHome = $env:ARCHLENS_JAVA_HOME,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$serviceRoot = Split-Path $PSScriptRoot -Parent
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) { throw '请指定 JDK 21 目录。' }
$modelNames = @('ARCHLENS_LLM_DISABLED','ARCHLENS_LLM_PROVIDER','ARCHLENS_LLM_ENDPOINT','ARCHLENS_LLM_MODEL','ARCHLENS_LLM_API_KEY','DEEPSEEK_API_KEY','DEEPSEEK_MODEL')
$previousModel = @{}
foreach ($name in $modelNames) { $previousModel[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    # 仅注入当前子进程；不输出、不持久化密钥。离线模式用于明确验证降级行为。
    foreach ($name in $modelNames) {
        if (-not [Environment]::GetEnvironmentVariable($name,'Process')) { [Environment]::SetEnvironmentVariable($name,[Environment]::GetEnvironmentVariable($name,'User'),'Process') }
    }
    if ($Offline) { $env:ARCHLENS_LLM_DISABLED = 'true' }
    if ($Command.EndsWith('-store')) {
        & (Join-Path $PSScriptRoot 'storage.ps1') -Command $Command -CommandArgs $CommandArgs -JdkHome $JdkHome
    } else {
        & (Join-Path $JdkHome 'bin/java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -jar (Join-Path $serviceRoot 'target/archlens-0.1.0-SNAPSHOT-cli.jar') $Command @CommandArgs
        if ($LASTEXITCODE -ne 0) { throw "Agent 命令失败，退出码 $LASTEXITCODE" }
    }
} finally { foreach ($name in $modelNames) { [Environment]::SetEnvironmentVariable($name,$previousModel[$name],'Process') } }
