# Biometric Fingerprint Unlock - Unified Installer
# Run this script as Administrator
[CmdletBinding()]
param()

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Please run this PowerShell script as Administrator!"
    Exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Starting Unified Installation..." -ForegroundColor Cyan

# 0. Stop existing service to free up file locks before building
$serviceName = "BiometricUnlockService"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Stopping existing service to allow build..." -ForegroundColor Yellow
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

# 1. Build the .NET Projects (Service & SetupApp)
Write-Host "Building Windows Service & Setup App (Release)..." -ForegroundColor Yellow
Set-Location -Path "$scriptDir"
& dotnet build "$scriptDir\Service\BiometricUnlockService.csproj" -c Release
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to build Service project."
    Exit 1
}

& dotnet build "$scriptDir\SetupApp\BiometricSetupApp.csproj" -c Release
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to build SetupApp project."
    Exit 1
}

# 2. Build the Credential Provider DLL (requires MSBuild)
Write-Host "Building Credential Provider (Release|x64)..." -ForegroundColor Yellow
$msbuildPath = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -requires Microsoft.Component.MSBuild -find MSBuild\**\Bin\MSBuild.exe
if (-not $msbuildPath) {
    Write-Warning "MSBuild not found. Attempting to use path-based MSBuild if available..."
    $msbuildPath = "msbuild"
}
& $msbuildPath "$scriptDir\CredentialProvider\FingerprintCredentialProvider.sln" /p:Configuration=Release /p:Platform=x64
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to build Credential Provider DLL. Please ensure C++ build tools are installed."
    Exit 1
}

# 3. Install the Windows Service
Write-Host "Installing Background Service..." -ForegroundColor Yellow
$exePath = [System.IO.Path]::GetFullPath("$scriptDir\Service\bin\Release\net10.0-windows10.0.19041.0\BiometricUnlockService.exe")

if ($existing) {
    & sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}

New-Service -Name $serviceName -BinaryPathName "`"$exePath`" --service" -DisplayName "Biometric Phone Unlock Service" -Description "Manages BLE GATT advertising and biometric credential verification for phone-based Windows unlock." -StartupType Automatic -ErrorAction Stop | Out-Null
Start-Service -Name $serviceName -ErrorAction Stop
Write-Host "Service installed and started successfully!" -ForegroundColor Green

# 4. Register Credential Provider DLL
Write-Host "Registering Credential Provider..." -ForegroundColor Yellow
$dllPath = [System.IO.Path]::GetFullPath("$scriptDir\CredentialProvider\bin\x64\Release\FingerprintCredentialProvider.dll")
& regsvr32.exe /s "$dllPath"
Write-Host "DLL Registered successfully!" -ForegroundColor Green

# 5. Create Desktop & Start Menu Shortcuts for the Setup App
Write-Host "Creating App Shortcuts..." -ForegroundColor Yellow
$setupAppPath = [System.IO.Path]::GetFullPath("$scriptDir\SetupApp\bin\Release\net10.0-windows10.0.19041.0\BiometricUnlockSetup.exe")

$wshShell = New-Object -ComObject WScript.Shell
$desktopPath = [System.Environment]::GetFolderPath('Desktop')
$startMenuPath = [System.Environment]::GetFolderPath('Programs')

# Desktop Shortcut
$shortcut = $wshShell.CreateShortcut("$desktopPath\Biometric Setup.lnk")
$shortcut.TargetPath = $setupAppPath
$shortcut.WorkingDirectory = Split-Path $setupAppPath
$shortcut.IconLocation = "shell32.dll, 47" # System Lock icon
$shortcut.Save()

# Start Menu Shortcut
$shortcutSm = $wshShell.CreateShortcut("$startMenuPath\Biometric Setup.lnk")
$shortcutSm.TargetPath = $setupAppPath
$shortcutSm.WorkingDirectory = Split-Path $setupAppPath
$shortcutSm.IconLocation = "shell32.dll, 47"
$shortcutSm.Save()

Write-Host "Shortcuts created on Desktop and Start Menu!" -ForegroundColor Green

Write-Host "`n===============================================" -ForegroundColor Cyan
Write-Host "🎉 INSTALLATION COMPLETE! 🎉" -ForegroundColor Green
Write-Host "1. Open 'Biometric Setup' on your Desktop to pair your phone."
Write-Host "2. Save your Windows credentials in the Setup App."
Write-Host "3. Press Win+L to lock your screen and test the unlock from your phone!"
Write-Host "===============================================" -ForegroundColor Cyan
