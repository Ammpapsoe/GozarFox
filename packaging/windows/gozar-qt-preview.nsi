Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"

!define PRODUCT "Gozar Qt Experimental"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\GozarQtExperimental"
Name "${PRODUCT}"
OutFile "${OUTPUT}"
RequestExecutionLevel user
SetCompressor /SOLID lzma
!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_WELCOMEPAGE_TITLE "Gozar Qt experimental test build"
!define MUI_WELCOMEPAGE_TEXT "Not a release. Quit every Gozar window before proceeding. This installs separate app files but uses the existing Gozar account and settings and can change network settings."
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function .onInit
  SetShellVarContext current
  ; Use a fixed, per-user path. Ignore any /D= override; never replace stable Gozar.
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\Gozar Qt Experimental"
  IfFileExists "$INSTDIR" occupied ok
occupied:
  MessageBox MB_ICONEXCLAMATION|MB_OK "Uninstall the previous Gozar Qt Experimental build first." /SD IDOK
  SetErrorLevel 2
  Abort
ok:
FunctionEnd

Section "Preview"
  SetShellVarContext current
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

Section "Uninstall"
  SetShellVarContext current
  ; Remove only the known deployed files; never delete a Gozar profile.
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\Gozar Qt Experimental"
  !include "${REMOVE_PAYLOAD}"
  IfErrors 0 +3
    SetErrorLevel 1
    Abort "Quit the experimental app before uninstalling."
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Gozar Qt Experimental.lnk"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
SectionEnd
