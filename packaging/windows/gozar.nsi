Unicode true
!include "MUI2.nsh"

!ifndef SOURCE
!error "SOURCE is required"
!endif
!ifndef OUTPUT
!error "OUTPUT is required"
!endif
!ifndef VERSION
!error "VERSION is required"
!endif
!ifndef LICENSE_FILE
!error "LICENSE_FILE is required"
!endif
!ifndef ICON_FILE
!error "ICON_FILE is required"
!endif
!ifndef WIZARD_FILE
!error "WIZARD_FILE is required"
!endif
!ifndef HEADER_FILE
!error "HEADER_FILE is required"
!endif

Name "Gozar"
Caption "Gozar Setup"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\Gozar"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ShowInstDetails show
ShowUninstDetails show
BrandingText "Gozar · Firefox VPN"
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "Gozar"
VIAddVersionKey "FileDescription" "Gozar Setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "CompanyName" "Gozar"
VIAddVersionKey "LegalCopyright" "Gozar contributors; see bundled licenses"

!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${WIZARD_FILE}"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${WIZARD_FILE}"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${HEADER_FILE}"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Welcome to Gozar"
!define MUI_WELCOMEPAGE_TEXT "Install Gozar and its complete desktop core on this device.$\r$\n$\r$\nThe app will request administrator approval when it starts because the VPN tunnel needs it."
!define MUI_FINISHPAGE_RUN "$INSTDIR\gozar.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Gozar"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH
!insertmacro MUI_LANGUAGE "English"

Function .onInit
    FindWindow $0 "FLUTTER_RUNNER_WIN32_WINDOW" "Gozar"
    StrCmp $0 0 ready
    IfSilent abort
    MessageBox MB_OK|MB_ICONEXCLAMATION "Close Gozar before installing or updating it."
  abort:
    SetErrorLevel 2
    Abort
  ready:
FunctionEnd

Function un.onInit
    FindWindow $0 "FLUTTER_RUNNER_WIN32_WINDOW" "Gozar"
    StrCmp $0 0 ready
    IfSilent abort
    MessageBox MB_OK|MB_ICONEXCLAMATION "Close Gozar before uninstalling it."
  abort:
    SetErrorLevel 2
    Abort
  ready:
FunctionEnd

Section "Gozar application and core" Main
    SectionIn RO
    SetShellVarContext current
    SetOutPath "$INSTDIR"
!ifdef NSIS_WIN32_MAKENSIS
    File /r "${SOURCE}\*"
!else
    File /r "${SOURCE}/*"
!endif
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    CreateShortCut "$SMPROGRAMS\Gozar.lnk" "$INSTDIR\gozar.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "DisplayName" "Gozar"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "DisplayVersion" "${VERSION}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "Publisher" "Gozar"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "DisplayIcon" "$INSTDIR\gozar.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "UninstallString" '"$INSTDIR\Uninstall.exe"'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar" "NoRepair" 1
SectionEnd

Section /o "Desktop shortcut" DesktopShortcut
    SetShellVarContext current
    CreateShortCut "$DESKTOP\Gozar.lnk" "$INSTDIR\gozar.exe"
SectionEnd

Section "Uninstall"
    SetShellVarContext current
    Delete "$SMPROGRAMS\Gozar.lnk"
    Delete "$DESKTOP\Gozar.lnk"
    Delete "$INSTDIR\gozar.exe"
    Delete "$INSTDIR\gozar-core.exe"
    Delete "$INSTDIR\flutter_windows.dll"
    Delete "$INSTDIR\screen_retriever_windows_plugin.dll"
    Delete "$INSTDIR\window_manager_plugin.dll"
    Delete "$INSTDIR\webview_windows_plugin.dll"
    Delete "$INSTDIR\WebView2Loader.dll"
    Delete "$INSTDIR\wintun.dll"
    Delete "$INSTDIR\LICENSE"
    Delete "$INSTDIR\NOTICE"
    Delete "$INSTDIR\WINTUN-LICENSE.txt"
    Delete "$INSTDIR\QUIC-GO-LICENSE.txt"
    Delete "$INSTDIR\QPACK-LICENSE.txt"
    Delete "$INSTDIR\GO-DEPS-LICENSES.txt"
    RMDir /r "$INSTDIR\data"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir "$INSTDIR"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Gozar"
SectionEnd

LangString DescriptionMain ${LANG_ENGLISH} "Install the app, tunnel core, libraries and licenses for the current user."
LangString DescriptionDesktop ${LANG_ENGLISH} "Add a Gozar shortcut to the desktop."
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
    !insertmacro MUI_DESCRIPTION_TEXT ${Main} $(DescriptionMain)
    !insertmacro MUI_DESCRIPTION_TEXT ${DesktopShortcut} $(DescriptionDesktop)
!insertmacro MUI_FUNCTION_DESCRIPTION_END
