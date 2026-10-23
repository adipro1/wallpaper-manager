; Inno Setup script for Wallpaper Manager.
; Built by `gradlew installer` in desktop/ (needs Inno Setup 6 on PATH: https://jrsoftware.org/isinfo.php),
; which passes AppVersion, SourceDir (the app folder from createDistributable) and OutputDir.
; Installs for the current user only, so no administrator rights are needed.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\desktop\build\compose\binaries\main\app\WallpaperManager"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist"
#endif
#define AppName "Wallpaper Manager"
#define AppExe "WallpaperManager.exe"

[Setup]
AppId={{8C7C2A5E-4A6B-4C1F-9E3D-2F1B7A9C5D41}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=adipro1
AppPublisherURL=https://github.com/adipro1/wallpaper-manager
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=WallpaperManager-{#AppVersion}-setup
SetupIconFile=..\desktop\src\main\resources\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Close a running copy before installing an update / uninstalling.
CloseApplications=force

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[InstallDelete]
; Version 1.x was a different program (Python); remove its files so they don't linger next to 2.0.
Type: filesandordirs; Name: "{app}\_internal"
Type: files; Name: "{app}\bin\libmpv-2.dll"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "READ ME FIRST.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName}"; Flags: nowait postinstall skipifsilent
; Silent installs come from the app's "Install now" (one-click update): start it again afterwards.
Filename: "{app}\{#AppExe}"; Flags: nowait skipifnotsilent

[UninstallRun]
; Stop the app so the live wallpaper windows go away and files can be removed.
Filename: "{sys}\taskkill.exe"; Parameters: "/IM {#AppExe} /F"; Flags: runhidden; RunOnceId: "StopApp"

[UninstallDelete]
; The screensaver copy of the launcher, made by the app when the screensaver is switched on.
Type: files; Name: "{app}\WallpaperManager.scr"

[Registry]
; The app adds this value itself when "Start with Windows" is on; remove it on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "WallpaperManager"; Flags: uninsdeletevalue dontcreatekey

; The user's library and settings in %LOCALAPPDATA%\WallpaperManager (including the downloaded
; web engine) are kept on purpose, so reinstalling doesn't lose them (see "READ ME FIRST.txt").
