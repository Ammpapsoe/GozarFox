param(
    [Parameter(Mandatory = $true)][string]$Installer,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [string]$VerifiedQtCore
)

$ErrorActionPreference = 'Stop'
$setup = (Resolve-Path $Installer).Path
$destination = [System.IO.Path]::GetFullPath($InstallDir)
if (Test-Path $destination) { throw 'Installer test directory already exists' }
$process = Start-Process -FilePath $setup -ArgumentList @('/S', "/D=$destination") -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Installer failed: $($process.ExitCode)" }
try {
    $required = @('gozar.exe', 'gozar-core.exe', 'flutter_windows.dll', 'screen_retriever_windows_plugin.dll', 'window_manager_plugin.dll', 'webview_windows_plugin.dll', 'LICENSE', 'NOTICE', 'GO-DEPS-LICENSES.txt', 'data/flutter_assets/NOTICES.Z', 'Uninstall.exe')
    foreach ($name in $required) {
        if (!(Test-Path (Join-Path $destination $name))) { throw "Installed file missing: $name" }
    }
    if ($VerifiedQtCore) {
        $requiredQt = @('gozar-original-ui.exe','Qt6Core.dll','Qt6Gui.dll','Qt6Widgets.dll','Qt6Svg.dll','platforms/qwindows.dll','vcruntime140.dll','msvcp140.dll','WINDSCRIBE-GPL-2-LICENSE.txt')
        foreach ($name in $requiredQt) {
            if (!(Test-Path (Join-Path $destination $name) -PathType Leaf)) { throw "Qt file missing: $name" }
        }
        if ((Get-FileHash (Join-Path $destination 'gozar-core.exe')).Hash -ne (Get-FileHash $VerifiedQtCore).Hash) { throw 'Installed core differs from same-commit package input' }
        $savedPath = $env:PATH
        try {
            $env:PATH = "$env:WINDIR\System32;$env:WINDIR"
            Remove-Item Env:QT_PLUGIN_PATH,Env:QT_QPA_PLATFORM_PLUGIN_PATH -ErrorAction SilentlyContinue
            $env:QT_QPA_PLATFORM = 'windows'
            $qt = Start-Process -FilePath (Join-Path $destination 'gozar-original-ui.exe') -ArgumentList '--ci-signed-out' -WorkingDirectory $destination -PassThru
            if (!$qt.WaitForExit(60000)) { throw 'Timed out waiting for installed Qt signed-out shutdown' }
            if ($qt.ExitCode -ne 0) { throw "Installed Qt signed-out smoke failed: $($qt.ExitCode)" }
        } finally { $env:PATH = $savedPath }
    }
} finally {
    $uninstaller = Join-Path $destination 'Uninstall.exe'
    $process = Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Uninstaller failed: $($process.ExitCode)" }
}
if (Test-Path $destination) { throw 'Uninstaller left the application directory behind' }
if (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar') { throw 'Uninstaller left its registry entry behind' }
Write-Output 'Windows installer and uninstaller verified.'
