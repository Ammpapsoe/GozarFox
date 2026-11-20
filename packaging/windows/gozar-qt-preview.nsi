Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"

!define PRODUCT "Gozar Qt Experimental"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\GozarQtExperimental"
Name "${PRODUCT}"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\Gozar Qt Experimental"
BrandingText "Gozar · Firefox VPN"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${WIZARD_FILE}"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${HEADER_FILE}"
!define MUI_WELCOMEPAGE_TITLE "Gozar Qt experimental test build"
!define MUI_WELCOMEPAGE_TEXT "Not a release. Quit every Gozar window before proceeding. This installs separate app files but uses the existing Gozar account and settings and can change network settings."
!define MUI_FINISHPAGE_RUN "$INSTDIR\gozar.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Gozar Qt Experimental"
!define MUI_FINISHPAGE_RUN_NOTCHECKED
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function .onInit
  SetShellVarContext current
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
  StrCmp $0 "" ok
  MessageBox MB_ICONEXCLAMATION|MB_OK "Uninstall the previous Gozar Qt Experimental build first." /SD IDOK
  SetErrorLevel 2
  Abort
ok:
  ; ReadRegStr sets the error flag when there is no previous install.
  ClearErrors
FunctionEnd

Function .onVerifyInstDir
  ; The preview always installs to a new folder, never into stable Gozar
  ; or an existing directory with unrelated files.
  IfFileExists "$INSTDIR" reject
  Return
reject:
  Abort
FunctionEnd

Function un.onInit
  SetShellVarContext current
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
  ${If} $0 == ""
  ${OrIf} $0 != $INSTDIR
    SetErrorLevel 2
    Abort
  ${EndIf}
FunctionEnd

Section "Gozar Qt application and core" Preview
  SectionIn RO
  SetShellVarContext current
  ; Also validate silent /D= installs, which bypass the directory picker.
  IfFileExists "$INSTDIR" 0 +3
    SetErrorLevel 2
    Abort "Choose a new folder for the preview."
  ClearErrors
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD_PATTERN}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "$SMPROGRAMS\Gozar Qt Experimental.lnk" "$INSTDIR\gozar.exe"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${PRODUCT}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\gozar.exe"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
  IfErrors 0 +3
    SetErrorLevel 1
    Abort "The preview could not be installed completely."
SectionEnd

Section /o "Create a desktop shortcut" DesktopLink
  SetShellVarContext current
  CreateShortcut "$DESKTOP\Gozar Qt Experimental.lnk" "$INSTDIR\gozar.exe"
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  ; Remove only the known deployed files; never delete a Gozar profile.
  ClearErrors
  !include "${REMOVE_PAYLOAD}"
  IfErrors 0 +3
    SetErrorLevel 1
    Abort "Quit the experimental app before uninstalling."
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Gozar Qt Experimental.lnk"
  Delete "$DESKTOP\Gozar Qt Experimental.lnk"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
SectionEnd
