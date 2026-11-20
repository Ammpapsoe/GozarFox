param(
  [Parameter(Mandatory=$true)][string]$Installer,
  [Parameter(Mandatory=$true)][string]$VerifiedCore
)
$ErrorActionPreference = 'Stop'
$setup = (Resolve-Path $Installer).Path
$core = (Resolve-Path $VerifiedCore).Path
$install = Join-Path $env:RUNNER_TEMP 'Gozar Qt Preview install smoke'
$shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Gozar Qt Experimental.lnk'
$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\GozarQtExperimental'
if ((Test-Path $install) -or (Test-Path $key) -or (Test-Path $shortcut)) { throw 'Preview install identities are already in use' }
function Invoke-Checked([string]$File, [string]$Flags, [string]$Directory) {
  $child = Start-Process -FilePath $File -ArgumentList $Flags -WorkingDirectory $Directory -PassThru
  if (!$child.WaitForExit(120000)) { Stop-Process -Id $child.Id -Force; throw "Timed out: $File" }
  if ($child.ExitCode -ne 0) { throw "Exit $($child.ExitCode): $File" }
}
# /D= must be the last NSIS argument and must not quote its path.
Invoke-Checked $setup "/S /D=$install" $env:RUNNER_TEMP
try {
  foreach ($name in @('gozar.exe','gozar-original-ui.exe','gozar-core.exe','flutter_windows.dll','screen_retriever_windows_plugin.dll','window_manager_plugin.dll','webview_windows_plugin.dll','WebView2Loader.dll','Qt6Core.dll','Qt6Gui.dll','Qt6Widgets.dll','Qt6Svg.dll','platforms\qwindows.dll','vcruntime140.dll','msvcp140.dll','data\flutter_assets\NOTICES.Z','EXPERIMENTAL-WARNING.txt','WINDSCRIBE-GPL-2-LICENSE.txt','Uninstall.exe')) {
    if (!(Test-Path (Join-Path $install $name) -PathType Leaf)) { throw "Missing installed file: $name" }
  }
  if (!(Test-Path $shortcut) -or (Get-ItemPropertyValue $key InstallLocation) -ne $install) { throw 'Preview shortcut or registry identity missing' }
  if ((Get-FileHash (Join-Path $install 'gozar-core.exe')).Hash -ne (Get-FileHash $core).Hash) { throw 'Installed core differs from verified release core' }
  $savedPath = $env:PATH
  try {
    # Ensure a missing deployed Qt DLL/plugin cannot be borrowed from install-qt-action.
    $env:PATH = "$env:WINDIR\System32;$env:WINDIR"
    Remove-Item Env:QT_PLUGIN_PATH,Env:QT_QPA_PLATFORM_PLUGIN_PATH -ErrorAction SilentlyContinue
    $env:QT_QPA_PLATFORM = 'windows'
    Invoke-Checked (Join-Path $install 'gozar-original-ui.exe') '--ci-signed-out' $install
  } finally { $env:PATH = $savedPath }
} finally {
  $uninstaller = Join-Path $install 'Uninstall.exe'
  if (Test-Path $uninstaller) {
    # NSIS copies the uninstaller to TEMP and launches a child. Wait for the tree.
    $removal = Start-Process -FilePath $uninstaller -ArgumentList '/S' -WorkingDirectory $env:RUNNER_TEMP -PassThru -Wait
    if ($removal.ExitCode -ne 0) { throw "Uninstall failed: $($removal.ExitCode)" }
  }
}
if ((Test-Path $install) -or (Test-Path $shortcut) -or (Test-Path $key)) { throw 'Preview uninstall left files or registration behind' }
Write-Host 'Checked installed Qt startup, signed-out official core, install layout and uninstall.'
