$ErrorActionPreference = 'Stop'
$docTaskRoot = Split-Path -Parent $PSScriptRoot
$wordTask = New-Object -ComObject Word.Application
$wordTask.Visible = $false
$wordTask.DisplayAlerts = 0
$openedTask = $null
try {
    $docTaskPath = (Get-ChildItem -LiteralPath $docTaskRoot -Filter '*.docx' | Select-Object -First 1).FullName
    Write-Output ('Opening ' + $docTaskPath)
    $openedTask = $wordTask.Documents.Open($docTaskPath, $false, $true, $false)
    Write-Output 'Opened document; exporting PDF'
    $openedTask.ExportAsFixedFormat((Join-Path $PSScriptRoot 'revised.pdf'),17)
    Write-Output ('Pages: ' + $openedTask.ComputeStatistics(2))
} finally {
    if ($null -ne $openedTask) { $openedTask.Close(0) }
    $wordTask.Quit()
}
