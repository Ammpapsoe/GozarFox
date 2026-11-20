$ErrorActionPreference = 'Stop'
$stage = Join-Path $env:RUNNER_TEMP 'desktop-qt-package-stage'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vs) { throw 'Visual Studio C++ tools not available' }
$redist = Get-ChildItem "$vs\VC\Redist\MSVC" -Directory | Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$redist) { throw 'Visual C++ Redistributable files not found' }
Copy-Item "$($redist.FullName)\x64\Microsoft.VC143.CRT\*.dll" $stage
$compiler = Join-Path ${env:ProgramFiles(x86)} 'NSIS\makensis.exe'
if (!(Test-Path $compiler)) { choco install nsis --no-progress -y }
if (!(Test-Path $compiler)) { throw 'NSIS compiler not available' }
$manifest = Join-Path $env:RUNNER_TEMP 'gozar-uninstall-files.nsh'
python packaging/windows/nsis-preview-payload.py $stage $manifest
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate uninstall manifest' }
$version = (Select-String '^version:\s+(\d+\.\d+\.\d+)\+' desktop_flutter/pubspec.yaml).Matches.Groups[1].Value
if (!$version) { throw 'No version found in the unmodified launcher' }
$root = $env:GITHUB_WORKSPACE
$installer = Join-Path $env:RUNNER_TEMP 'packages/Gozar-Windows-x64-Setup.exe'
& $compiler /V2 /WX "/DSOURCE=$stage" "/DOUTPUT=$installer" "/DVERSION=$version" "/DLICENSE_FILE=$root\LICENSE" "/DICON_FILE=$root\desktop_flutter\windows\runner\resources\app_icon.ico" "/DWIZARD_FILE=$root\packaging\windows\wizard.bmp" "/DHEADER_FILE=$root\packaging\windows\header.bmp" "/DREMOVE_QT_PAYLOAD=$manifest" packaging/windows/gozar.nsi
if ($LASTEXITCODE -ne 0) { throw 'Failed to compile official-layout NSIS setup' }
$core = Join-Path $env:RUNNER_TEMP 'same-commit/gozar-core.exe'
./scripts/test_windows_installer.ps1 -Installer $installer -InstallDir "$env:RUNNER_TEMP/Gozar package smoke" -VerifiedQtCore $core
# Only validation mode records oversize packages instead of failing.
$zip = Join-Path $env:RUNNER_TEMP 'packages/Gozar-Windows-x64.zip'
$archiveArgs = @('--qt-desktop')
if ($env:GOZAR_OFFICIAL_PACKAGE -ne '1') { $archiveArgs += '--record-over-budget' }
python scripts/archive_windows.py $stage $zip @archiveArgs
if ($LASTEXITCODE -ne 0) { throw 'Windows ZIP construction failed' }
