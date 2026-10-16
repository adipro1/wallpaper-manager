; Inno Setup script for Wallpaper Manager.
; Built by tools/build.py --installer (needs Inno Setup 6: https://jrsoftware.org/isinfo.php).
; Installs for the current user only, so no administrator rights are needed.

#ifndef AppVersion
  #define AppVersion "0.0.0"
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
OutputDir=..\dist
OutputBaseFilename=WallpaperManager-{#AppVersion}-setup
SetupIconFile=..\wallpaper_manager\assets\icon.ico
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

[Files]
Source: "..\dist\WallpaperManager\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Stop the app so the live wallpaper window goes away and files can be removed.
Filename: "{sys}\taskkill.exe"; Parameters: "/IM {#AppExe} /F"; Flags: runhidden; RunOnceId: "StopApp"

[Registry]
; The app adds this value itself when "Start with Windows" is on; remove it on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "WallpaperManager"; Flags: uninsdeletevalue dontcreatekey

; The user's library and settings in %LOCALAPPDATA%\WallpaperManager are kept on purpose,
; so reinstalling doesn't lose them (see "READ ME FIRST.txt" to remove them by hand).
