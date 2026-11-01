param(
  [Parameter(Mandatory=$true)][string]$Bundle,
  [Parameter(Mandatory=$true)][string]$Core,
  [Parameter(Mandatory=$true)][string]$TestLog,
  [Parameter(Mandatory=$true)][string]$Screenshot
)

$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class GozarWindowProbe {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowRgn(IntPtr hWnd, IntPtr region);
  [DllImport("gdi32.dll")] public static extern IntPtr CreateRectRgn(int left, int top, int right, int bottom);
  [DllImport("gdi32.dll")] public static extern bool PtInRegion(IntPtr region, int x, int y);
  [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr obj);
}
'@
$env:GOZAR_CORE = $Core
$env:GOZAR_UI_TEST_LOG = $TestLog
$env:APPDATA = Join-Path $env:RUNNER_TEMP 'gozar-ui-smoke'
New-Item -ItemType Directory -Force -Path $env:APPDATA | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $TestLog
$process = Start-Process -FilePath (Join-Path $Bundle 'gozar.exe') -PassThru
try {
  $handle = [IntPtr]::Zero
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    $process.Refresh()
    if ($process.HasExited) { throw 'The Windows application exited during startup.' }
    if ($process.MainWindowHandle -ne [IntPtr]::Zero) { $handle = $process.MainWindowHandle; break }
  }
  if ($handle -eq [IntPtr]::Zero) { throw 'No native window appeared.' }
  for ($i = 0; $i -lt 60; $i++) {
    if ((Test-Path $TestLog) -and ((Get-Content $TestLog) -contains 'state')) { break }
    Start-Sleep -Milliseconds 500
  }
  if (!(Test-Path $TestLog) -or !((Get-Content $TestLog) -contains 'state')) { throw 'The UI did not start its simulated test core.' }
  Start-Sleep -Seconds 3
  $rect = New-Object GozarWindowProbe+RECT
  if (![GozarWindowProbe]::GetWindowRect($handle, [ref]$rect)) { throw 'Could not inspect the window.' }
  $width = $rect.Right - $rect.Left
  $height = $rect.Bottom - $rect.Top
  if ($width -lt 320 -or $width -gt 900 -or $height -lt 320 -or $height -gt 900) { throw "Unexpected native window size: ${width}x${height}" }
  $region = [GozarWindowProbe]::CreateRectRgn(0, 0, 0, 0)
  if ($region -eq [IntPtr]::Zero) { throw 'Could not allocate the window region.' }
  try {
    if ([GozarWindowProbe]::GetWindowRgn($handle, $region) -eq 0) { throw 'The rounded native window region is missing.' }
    if ([GozarWindowProbe]::PtInRegion($region, 0, 0) -or ![GozarWindowProbe]::PtInRegion($region, [int]($width / 2), [int]($height / 2))) { throw 'The native window region is not rounded.' }
  } finally {
    [void][GozarWindowProbe]::DeleteObject($region)
  }
  try {
    Add-Type -AssemblyName System.Drawing
    [void][GozarWindowProbe]::SetForegroundWindow($handle)
    Start-Sleep -Milliseconds 500
    $image = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($image)
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $image.Size)
    $image.Save($Screenshot, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $image.Dispose()
  } catch {
    Write-Host 'Screenshot unavailable in this runner session.'
  }
  if (![GozarWindowProbe]::PostMessage($handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)) { throw 'Could not send native close.' }
  if (!$process.WaitForExit(60000)) { throw 'The native window did not close.' }
  $events = @(Get-Content $TestLog)
  $disconnect = [array]::IndexOf($events, 'disconnect')
  $quit = [array]::IndexOf($events, 'quit')
  if ($disconnect -lt 0 -or $quit -lt $disconnect) { throw 'The simulated core did not disconnect before quitting.' }
  Write-Host "Native Windows smoke test passed with simulated core (${width}x${height})."
} finally {
  if (!$process.HasExited) { Stop-Process -Id $process.Id -Force }
  Remove-Item Env:GOZAR_CORE, Env:GOZAR_UI_TEST_LOG -ErrorAction SilentlyContinue
}
