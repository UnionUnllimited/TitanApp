; Titan VPS for Windows: our own installer (Inno Setup) instead of the bare MSI dialog.
; Saved as UTF-8 with BOM: Inno Setup reads Cyrillic correctly only then.
; Built in CI: ISCC /DAppVersion=1.0.<run> /DAppDir=<createDistributable output> titan.iss
; Silent update from the app: titan-vps-setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppDir
  #define AppDir "..\build\compose\binaries\main\app\Titan VPS"
#endif

[Setup]
AppId={{6F3B2A8E-4C1D-4E57-9A0B-7D2E5C9F1A35}
AppName=Titan VPS
AppVersion={#AppVersion}
AppVerName=Titan VPS {#AppVersion}
AppPublisher=Titan VPS
VersionInfoVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\Titan VPS
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\out\inno
OutputBaseFilename=titan-vps-setup
SetupIconFile=..\icons\icon.ico
UninstallDisplayIcon={app}\Titan VPS.exe
UninstallDisplayName=Titan VPS
WizardStyle=modern
WizardSizePercent=110
WizardImageFile=wizard.bmp,wizard_125.bmp,wizard_150.bmp,wizard_200.bmp
WizardSmallImageFile=small.bmp,small_125.bmp,small_150.bmp,small_200.bmp
Compression=lzma2/ultra64
SolidCompression=yes
CloseApplications=force
RestartApplications=no

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"

[Messages]
ru.WelcomeLabel1=Установка Titan VPS
ru.WelcomeLabel2=Быстрый и стабильный VPN для вашего компьютера.%n%nНажмите «Установить», чтобы продолжить.
ru.FinishedHeadingLabel=Titan VPS установлен
ru.FinishedLabelNoIcons=Можно пользоваться. Вставьте ключ подписки из бота — и подключайтесь.
ru.FinishedLabel=Можно пользоваться. Вставьте ключ подписки из бота — и подключайтесь.

[Tasks]
Name: "desktopicon"; Description: "Ярлык на рабочем столе"; GroupDescription: "Дополнительно:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[InstallDelete]
; Files of the previous version that a new one may no longer have.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Icons]
Name: "{userprograms}\Titan VPS"; Filename: "{app}\Titan VPS.exe"
Name: "{userdesktop}\Titan VPS"; Filename: "{app}\Titan VPS.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Titan VPS.exe"; Description: "Запустить Titan VPS"; Flags: nowait postinstall skipifsilent
; After a silent update from the app: start it again.
Filename: "{app}\Titan VPS.exe"; Flags: nowait; Check: WizardSilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
// Our cores keep files locked; stop only ours (by path), not other programs' xray.
procedure StopCores(Dir: String);
var
  Code: Integer;
begin
  Exec('powershell.exe',
    '-NoProfile -WindowStyle Hidden -Command "Get-Process xray,sing-box -ErrorAction SilentlyContinue | ' +
    'Where-Object { $_.Path -like ''' + Dir + '*'' } | Stop-Process -Force"',
    '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

// Versions up to now were installed as MSI ("Titan VPS"): remove that copy quietly,
// so the user doesn't end up with two apps.
procedure RemoveOldMsi(Root: Integer);
var
  Keys: TArrayOfString;
  I, Code: Integer;
  IsMsi: Cardinal;
  Name, Base: String;
begin
  Base := 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  if not RegGetSubkeyNames(Root, Base, Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    if RegQueryStringValue(Root, Base + '\' + Keys[I], 'DisplayName', Name) and (Name = 'Titan VPS') then
    begin
      // Only the MSI entry (WindowsInstaller=1), never our own "{…}_is1".
      if RegQueryDWordValue(Root, Base + '\' + Keys[I], 'WindowsInstaller', IsMsi) and (IsMsi = 1) then
        Exec('msiexec.exe', '/x ' + Keys[I] + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, Code);
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    StopCores(ExpandConstant('{localappdata}'));
    RemoveOldMsi(HKCU);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    StopCores(ExpandConstant('{app}'));
end;
