param([string]$JdkHome = $env:ARCHLENS_JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { throw 'Pass -JdkHome with a JDK 21 directory.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $artifact = 'target/archlens-0.1.0-SNAPSHOT-cli.jar'
    $reportPath = 'target/final-report-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.json'
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
        $deps = Get-Content -LiteralPath 'docs/runtime-dependencies.json' -Raw | ConvertFrom-Json
        foreach ($dep in $deps) {
            if ($dep.licenseScope -like 'EMBEDDED_METADATA_ONLY*') { continue }
            $parts = $dep.coordinate.Split(':')
            $jarPath = '.local/m2/' + $parts[0].Replace('.','/') + '/' + $parts[1] + '/' + $parts[2] + '/' + $parts[1] + '-' + $parts[2] + '.jar'
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
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath 'docs/final-smoke.json' -Encoding utf8
    Write-Output "Smoke passed; $licenseChecks third-party notice checks passed."
} finally { Pop-Location }
