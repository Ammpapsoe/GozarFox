param(
    [Parameter(Mandatory = $true)][string]$Installer,
    [Parameter(Mandatory = $true)][string]$InstallDir
)

$ErrorActionPreference = 'Stop'
$setup = (Resolve-Path $Installer).Path
$destination = [System.IO.Path]::GetFullPath($InstallDir)
if (Test-Path $destination) { throw 'Installer test directory already exists' }
$process = Start-Process -FilePath $setup -ArgumentList @('/S', "/D=$destination") -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Installer failed: $($process.ExitCode)" }
$required = @('gozar.exe', 'gozar-core.exe', 'flutter_windows.dll', 'screen_retriever_windows_plugin.dll', 'window_manager_plugin.dll', 'LICENSE', 'NOTICE', 'GO-DEPS-LICENSES.txt', 'data/flutter_assets/NOTICES.Z', 'Uninstall.exe')
foreach ($name in $required) {
    if (!(Test-Path (Join-Path $destination $name))) { throw "Installed file missing: $name" }
}
$uninstaller = Join-Path $destination 'Uninstall.exe'
$process = Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Uninstaller failed: $($process.ExitCode)" }
if (Test-Path $destination) { throw 'Uninstaller left the application directory behind' }
if (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar') { throw 'Uninstaller left its registry entry behind' }
Write-Output 'Windows installer and uninstaller verified.'
