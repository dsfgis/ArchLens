param([string]$JdkHome = $env:ARCHLENS_JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { throw 'Pass -JdkHome with a JDK 21 directory.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $artifact = 'target/archlens-0.1.0-SNAPSHOT-cli.jar'
    # 新验收产物独立输出，保留历史报告；唯一标识避免同毫秒运行相互覆盖。
    $verificationDirectory = 'target/verification'
    [IO.Directory]::CreateDirectory((Join-Path (Get-Location) $verificationDirectory)) | Out-Null
    $runStamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N')
    $reportPath = "$verificationDirectory/final-report-$runStamp.json"
    $summaryPath = "$verificationDirectory/final-smoke-$runStamp.json"
    & (Join-Path $JdkHome 'bin/java.exe') -jar $artifact analyze examples/column-rename/request.json $reportPath
    if ($LASTEXITCODE -ne 0) { throw "CLI failed: $LASTEXITCODE" }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.analysis.status -ne 'PARTIAL' -or $report.graph.nodes.Count -ne 3 -or $report.graph.edges.Count -ne 2 -or $report.graph.diagnostics.Count -ne 3) {
        throw 'Unexpected example graph or quality.'
    }
    $method = $report.graph.nodes | Where-Object { $_.type -eq 'METHOD' }
    $impact = $report.analysis.impacts | Where-Object { $_.nodeId -eq $method.nodeId }
    if ($impact.changeRequired -ne 'YES' -or $impact.risk.min -ne 32 -or $impact.risk.max -ne 92) { throw 'Unexpected rename impact or risk interval.' }
    $uber = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $artifact))
    $licenseChecks = 0
    try {
        # 依赖清单属于运行资料，文档归档不能移除烟测所需输入。
        $deps = Get-Content -LiteralPath 'runtime/runtime-dependencies.json' -Raw | ConvertFrom-Json
        # 对照实际运行类路径及产物内 Maven 元数据，旧清单漏项或错版本时要求先刷新。
        $expected = @{}
        $resolved = @{}
        $repoRoot = (Resolve-Path -LiteralPath '.local/m2').Path
        $classpathFile = 'target/runtime-classpath.txt'
        if (-not (Test-Path -LiteralPath $classpathFile)) { throw 'Build first and run scripts/record-runtime.ps1 before smoke.' }
        foreach ($jar in ((Get-Content -LiteralPath $classpathFile -Raw).Trim().Split([IO.Path]::PathSeparator))) {
            $relative = [IO.Path]::GetRelativePath($repoRoot,$jar).Replace('\','/')
            if ($relative.StartsWith('../') -or [IO.Path]::IsPathRooted($relative)) { throw 'Runtime dependency is outside the configured project Maven repository.' }
            $segments = $relative.Split('/')
            if ($segments.Length -lt 4) { throw 'Invalid Maven repository artifact path.' }
            $key = ($segments[0..($segments.Length-4)] -join '.') + ':' + $segments[$segments.Length-3] + ':' + $segments[$segments.Length-2]
            $expected[$key] = $true
            $resolved[$key] = $jar
        }
        foreach ($entry in $uber.Entries) {
            if ($entry.FullName -notmatch '^META-INF/maven/.+/pom.properties$') { continue }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $properties = ConvertFrom-StringData $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($properties.groupId -eq 'io.archlens') { continue }
            $key = $properties.groupId + ':' + $properties.artifactId + ':' + $properties.version
            $expected[$key] = $true
        }
        $listed = @{}
        foreach ($dep in $deps) {
            if (-not $dep.coordinate -or $listed.ContainsKey($dep.coordinate) -or -not $expected.ContainsKey($dep.coordinate)) { throw 'Stale or duplicate runtime dependency list; run scripts/record-runtime.ps1.' }
            $listed[$dep.coordinate] = $true
        }
        if ($listed.Count -ne $expected.Count) { throw 'Runtime dependency list is incomplete; run scripts/record-runtime.ps1.' }
        foreach ($dep in $deps) {
            if ($dep.licenseScope -like 'EMBEDDED_METADATA_ONLY*') {
                if ($resolved.ContainsKey($dep.coordinate)) { throw "Resolved dependency lacks recorded provenance: $($dep.coordinate)" }
                continue
            }
            $parts = $dep.coordinate.Split(':')
            $jarPath = '.local/m2/' + $parts[0].Replace('.','/') + '/' + $parts[1] + '/' + $parts[2] + '/' + $parts[1] + '-' + $parts[2] + '.jar'
            # 校验记录时的原始 JAR 哈希，不能用旧清单跳过新增驱动的声明检查。
            if (-not $dep.sha256 -or (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dep.sha256) { throw "Runtime dependency hash changed: $($dep.coordinate); run scripts/record-runtime.ps1." }
            $source = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $jarPath))
            try {
                foreach ($name in @('META-INF/LICENSE','META-INF/NOTICE','META-INF/LICENSE.txt','META-INF/NOTICE.txt','LICENSE','THIRD-PARTY')) {
                    $entry = $source.GetEntry($name)
                    if (-not $entry) { continue }
                    $packed = $uber.GetEntry($name)
                    if (-not $packed) { throw "Missing packed notice: $name" }
                    $reader = [IO.StreamReader]::new($entry.Open())
                    try { $originalText = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
                    $reader = [IO.StreamReader]::new($packed.Open())
                    try { $packedText = $reader.ReadToEnd() } finally { $reader.Dispose() }
                    if (-not $packedText.Contains($originalText)) { throw "Notice text missing from package: $($dep.coordinate)/$name" }
                    $licenseChecks++
                }
            } finally { $source.Dispose() }
        }
    } finally { $uber.Dispose() }
    [ordered]@{
        checkedAtUtc = [DateTime]::UtcNow.ToString('o')
        artifact = $artifact
        artifactSha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        report = $reportPath
        reportSha256 = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
        status = 'PASS'
        analysisStatus = $report.analysis.status
        nodes = $report.graph.nodes.Count
        edges = $report.graph.edges.Count
        diagnostics = $report.graph.diagnostics.Count
        mapperChangeRequired = $impact.changeRequired
        mapperRiskInterval = @($impact.risk.min,$impact.risk.max)
        preservedNoticeChecks = $licenseChecks
    } | ConvertTo-Json -Depth 4 | Out-File -LiteralPath $summaryPath -Encoding utf8 -NoClobber
    Write-Output "Smoke passed; $licenseChecks third-party notice checks passed. Summary: $summaryPath"
} finally { Pop-Location }
