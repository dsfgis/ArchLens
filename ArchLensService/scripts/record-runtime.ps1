param([string]$Artifact = 'target/archlens-0.1.0-SNAPSHOT-cli.jar')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Artifact))
    $coordinates = @{}
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '^META-INF/maven/.+/pom.properties$') { continue }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $properties = ConvertFrom-StringData $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($properties.groupId -eq 'io.archlens') { continue }
            $key = $properties.groupId + ':' + $properties.artifactId + ':' + $properties.version
            $coordinates[$key] = $properties
        }
    } finally { $archive.Dispose() }
    # Some resolved JARs (including pgJDBC) omit Maven metadata from their binary.
    # Include the build's actual runtime classpath, not just pom.properties found in the shaded artifact.
    $classpathFile = 'target/runtime-classpath.txt'
    if (-not (Test-Path -LiteralPath $classpathFile)) { throw 'Build with mvn verify first to record the resolved runtime classpath.' }
    $repoRoot = (Resolve-Path -LiteralPath '.local/m2').Path
    foreach ($jar in ((Get-Content -LiteralPath $classpathFile -Raw).Trim().Split([IO.Path]::PathSeparator))) {
        $relative = [IO.Path]::GetRelativePath($repoRoot,$jar).Replace('\','/')
        if ($relative.StartsWith('../')) { throw 'Runtime dependency is outside the configured project Maven repository.' }
        $segments = $relative.Split('/')
        if ($segments.Length -lt 4) { throw 'Invalid Maven repository artifact path.' }
        $groupId = ($segments[0..($segments.Length-4)] -join '.')
        $artifactId = $segments[$segments.Length-3]
        $version = $segments[$segments.Length-2]
        $key = $groupId + ':' + $artifactId + ':' + $version
        $coordinates[$key] = @{groupId=$groupId;artifactId=$artifactId;version=$version}
    }
    $items = foreach ($key in ($coordinates.Keys | Sort-Object)) {
        $p = $coordinates[$key]
        $directory = Join-Path '.local/m2' ($p.groupId.Replace('.','/') + '/' + $p.artifactId + '/' + $p.version)
        $stem = Join-Path $directory ($p.artifactId + '-' + $p.version)
        if (-not (Test-Path -LiteralPath ($stem + '.pom')) -or -not (Test-Path -LiteralPath ($stem + '.jar'))) {
            [ordered]@{
                coordinate = $key
                sha256 = $null
                declaredLicenses = @()
                licenseScope = 'EMBEDDED_METADATA_ONLY; no standalone resolved artifact; review upstream bundled notices'
            }
            continue
        }
        [xml]$pom = Get-Content -LiteralPath ($stem + '.pom') -Raw
        $licenses = @($pom.project.licenses.license | Where-Object { $_ } | ForEach-Object {
            @{ name = [string]$_.name; url = [string]$_.url }
        })
        [ordered]@{
            coordinate = $key
            sha256 = (Get-FileHash -LiteralPath ($stem + '.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
            declaredLicenses = $licenses
            licenseScope = 'DIRECT_POM_ONLY; inherited or embedded notices require release review'
        }
    }
    $items | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath 'docs/runtime-dependencies.json' -Encoding utf8
    Write-Output "Recorded $($coordinates.Count) runtime/embedded coordinates with provenance."
} finally { Pop-Location }
