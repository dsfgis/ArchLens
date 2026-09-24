param(
    [string]$JdkHome = $env:ARCHLENS_JAVA_HOME,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$serviceRoot = Split-Path $PSScriptRoot -Parent
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw '请指定 JDK 21 目录。' }
$jar = Join-Path $serviceRoot 'target/archlens-0.1.0-SNAPSHOT-cli.jar'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $serviceRoot ('target/scenario-smoke-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
}
# 输出目录必须全新，防止覆盖之前的验收证据。
if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录已存在，请使用新目录。' }
$null = New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$cases = [ordered]@{
    'mysql-postgresql' = @('MYSQL_UNSIGNED','MYSQL_AUTO_INCREMENT','MYSQL_BACKTICK','MYSQL_IFNULL','MYSQL_SIGNED_INT')
    'oracle-postgresql' = @('ORACLE_TYPE','ORACLE_DATE','ORACLE_EMPTY_STRING','ORACLE_NVL','ORACLE_NEXTVAL')
    'csharp-java' = @('CS_DECIMAL','CS_UNSIGNED','CS_AWAIT','CS_SERIALIZATION')
    'java-refactor' = @('JAVA_API_CHANGE','JAVA_BODY_CHANGE','JAVA_STATE_CHANGE')
    'spring-boot' = @('BOOT_JAKARTA','BOOT_JAVA17','BOOT_SERVLET_DEPENDENCY')
    'jdk-upgrade' = @('JDK_JAXB')
    'httpclient-upgrade' = @('HTTPCLIENT_NAMESPACE')
}
$results = @()
foreach ($name in $cases.Keys) {
    $inputPath = Join-Path $serviceRoot "examples/scenarios/$name/investigation.json"
    $reportPath = Join-Path $OutputDirectory "$name.json"
    & $java '-Dfile.encoding=UTF-8' -jar $jar investigate $inputPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "场景运行失败：$name" }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $findings = @($report.findings | Where-Object { $null -ne $_.rule })
    $ids = @($findings | ForEach-Object { $_.rule.ruleId } | Sort-Object -Unique)
    foreach ($expected in $cases[$name]) {
        if ($ids -notcontains $expected) { throw "规则缺失：$name / $expected" }
    }
    if ($report.status -ne 'PARTIAL' -or $report.schemaVersion -ne 'archlens.investigation-report.v2') { throw "报告状态或版本错误：$name" }
    foreach ($finding in $findings) {
        if (@($finding.evidence).Count -eq 0 -or @($finding.recommendations).Count -eq 0) { throw "发现缺少证据或建议：$name" }
    }
    $results += [ordered]@{
        scenario = $name; report = "$name.json"; status = $report.status
        ruleIds = $ids; findingCount = $findings.Count; gapCount = @($report.coverageGaps).Count
        sha256 = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
# 额外验证打包产物能导出完整规则元数据；不保存环境变量和数据库配置。
$catalogJson = & $java '-Dfile.encoding=UTF-8' -jar $jar rules
if ($LASTEXITCODE -ne 0) { throw '规则清单导出失败。' }
$catalog = ($catalogJson -join "`n") | ConvertFrom-Json
if (@($catalog).Count -ne 25) { throw '规则数量变化，请同步烟测声明。' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'rules.json'), ($catalogJson -join "`n"), [Text.UTF8Encoding]::new($false))
$summary = [ordered]@{
    verifiedAt = [DateTime]::UtcNow.ToString('o'); rulePackage = 'archlens-rules-1.0'
    jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    ruleCount = @($catalog).Count; cases = $results
}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'summary.json'), ($summary | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
Write-Output "场景烟测通过：$OutputDirectory"
