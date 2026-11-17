param([Parameter(Mandatory = $true)][string]$Exe)

$ErrorActionPreference = 'Stop'
$directory = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
$tool = Get-ChildItem -Path $directory -Filter mt.exe -Recurse |
    Where-Object { $_.Directory.Name -eq 'x64' } |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if ($null -eq $tool) { throw 'Windows SDK manifest tool was not found' }
$binary = (Resolve-Path $Exe).Path
$output = Join-Path $env:RUNNER_TEMP 'gozar-executable.manifest'
& $tool.FullName -nologo "-inputresource:$binary;#1" "-out:$output"
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the executable manifest' }
[xml]$manifest = Get-Content -Raw $output
$level = $manifest.SelectSingleNode("//*[local-name()='requestedExecutionLevel']")
if ($null -eq $level -or $level.level -ne 'requireAdministrator') {
    throw 'The executable does not request administrator privileges'
}
Write-Output 'Windows administrator manifest verified.'
