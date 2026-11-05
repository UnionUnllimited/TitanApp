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
// Text for a PowerShell single-quoted string: its quote marks doubled, the curly ones too
// (PowerShell takes them for quotes as well), or a profile folder like O'Brien breaks the command.
function PsQuoted(S: String): String;
var
  Quotes, Q: String;
  I: Integer;
begin
  Result := S;
  Quotes := '''' + #$2018 + #$2019 + #$201A + #$201B;
  for I := 1 to Length(Quotes) do
  begin
    Q := Quotes[I];
    StringChangeEx(Result, Q, Q + Q, True);
  end;
end;

// The app first (it would start a core stopped under it again), then our cores and clients,
// which keep files locked; only ours (by path), not other programs' xray. True when no Titan VPS
// is left running: one with administrator rights can't be stopped from here, and its system
// proxy is then its own to put back.
function StopApp(Dir: String): Boolean;
var
  Code: Integer;
  Ours: String;
begin
  Ours := '$_.Path -like ''' + PsQuoted(Dir) + '*''';
  Result := Exec('powershell.exe',
    '-NoProfile -WindowStyle Hidden -Command "' +
    '$app = Get-Process ''Titan VPS'' -ErrorAction SilentlyContinue | Where-Object { ' + Ours + ' }; ' +
    'if ($app) { $app | Stop-Process -Force; $app | Wait-Process -Timeout 5 -ErrorAction SilentlyContinue }; ' +
    'Get-Process xray,sing-box,naive,mieru -ErrorAction SilentlyContinue | ' +
    'Where-Object { ' + Ours + ' } | Stop-Process -Force; ' +
    'if (Get-Process ''Titan VPS'' -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }"',
    '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0);
end;

function InternetSetOption(hInternet: Longint; dwOption: Cardinal; lpBuffer: Longint; dwBufferLength: Cardinal): Longint;
  external 'InternetSetOptionW@wininet.dll stdcall delayload';

// A value from the app's proxy-backup.json (org.json: "key":"text" or "key":number), '' if none.
function BackupValue(const Json, Key: String): String;
var
  I: Integer;
begin
  Result := '';
  I := Pos('"' + Key + '":', Json);
  if I = 0 then Exit;
  I := I + Length(Key) + 3;
  if (I <= Length(Json)) and (Json[I] = '"') then
  begin
    I := I + 1;
    while (I <= Length(Json)) and (Json[I] <> '"') do
    begin
      if (Json[I] = '\') and (I < Length(Json)) then
        I := I + 1;
      Result := Result + Json[I];
      I := I + 1;
    end;
  end
  else
    while (I <= Length(Json)) and (Json[I] <> ',') and (Json[I] <> '}') do
    begin
      Result := Result + Json[I];
      I := I + 1;
    end;
end;

// The app was stopped (or had crashed) with its system proxy on: the settings from before it
// come back, or browsers would keep pointing at a core that is gone. Exe: the app, which puts
// back all of it with --restore-proxy (its own SystemProxy.restore()): also «Определять
// параметры автоматически», the setup script and dial-up or VPN connections, which only WinINet
// sets. Without it, or if it fails: the registry values here; with Exe '' the backup stays, so
// the new version puts back the rest.
procedure RestoreProxy(Exe: String);
var
  Backup, Json, Key, Value: String;
  Raw: AnsiString;
  Code: Integer;
begin
  Backup := ExpandConstant('{userappdata}\TitanVPS\proxy-backup.json');
  if not FileExists(Backup) then Exit;
  if (Exe <> '') and FileExists(Exe) then
    if Exec(Exe, '--restore-proxy', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0) and not FileExists(Backup) then
      Exit;
  if not LoadStringFromFile(Backup, Raw) then Exit;
  Json := String(Raw);
  Key := 'Software\Microsoft\Windows\CurrentVersion\Internet Settings';
  RegWriteDWordValue(HKCU, Key, 'ProxyEnable', StrToIntDef(Trim(BackupValue(Json, 'enable')), 0));
  Value := BackupValue(Json, 'server');
  if Value = '' then RegDeleteValue(HKCU, Key, 'ProxyServer') else RegWriteStringValue(HKCU, Key, 'ProxyServer', Value);
  Value := BackupValue(Json, 'override');
  if Value = '' then RegDeleteValue(HKCU, Key, 'ProxyOverride') else RegWriteStringValue(HKCU, Key, 'ProxyOverride', Value);
  Value := BackupValue(Json, 'pac');
  if Value <> '' then RegWriteStringValue(HKCU, Key, 'AutoConfigURL', Value);
  if Exe <> '' then DeleteFile(Backup);
  // INTERNET_OPTION_SETTINGS_CHANGED, INTERNET_OPTION_REFRESH: running programs re-read them.
  InternetSetOption(0, 39, 0, 0);
  InternetSetOption(0, 37, 0, 0);
end;

// Start with Windows as the app set it: the Run key value, and the logon task (TUN). The task
// was made with administrator rights, which this uninstaller doesn't have; if Windows refuses,
// it stays and fails quietly at logon (its program is gone).
procedure RemoveAutostart;
var
  Code: Integer;
begin
  RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Titan VPS');
  Exec('schtasks.exe', '/delete /tn "Titan VPS" /f', '', SW_HIDE, ewWaitUntilTerminated, Code);
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

var
  AppStopped: Boolean;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    // An app stopped here can't put its system proxy back itself: the registry values now,
    // the rest by the new version once it is in place (before [Run] starts it).
    AppStopped := StopApp(ExpandConstant('{localappdata}'));
    if AppStopped then
      RestoreProxy('');
    RemoveOldMsi(HKCU);
  end;
  if (CurStep = ssPostInstall) and AppStopped then
    RestoreProxy(ExpandConstant('{app}\Titan VPS.exe'));
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    // Its files are still here: the app itself puts everything back; nothing starts after this.
    if StopApp(ExpandConstant('{app}')) then
      RestoreProxy(ExpandConstant('{app}\Titan VPS.exe'));
    RemoveAutostart;
  end;
end;
