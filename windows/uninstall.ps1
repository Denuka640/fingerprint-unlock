# Biometric Fingerprint Unlock - Unified Uninstaller
# Run this script as Administrator
[CmdletBinding()]
param()

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Please run this PowerShell script as Administrator!"
    Exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Starting Uninstallation..." -ForegroundColor Cyan

# 1. Unregister Credential Provider DLL
Write-Host "Unregistering Credential Provider..." -ForegroundColor Yellow
$dllPath = [System.IO.Path]::GetFullPath("$scriptDir\CredentialProvider\bin\x64\Release\FingerprintCredentialProvider.dll")
if (Test-Path $dllPath) {
    & regsvr32.exe /u /s "$dllPath"
    Write-Host "DLL Unregistered successfully!" -ForegroundColor Green
} else {
    Write-Warning "DLL not found, skipping unregistration."
}

# 2. Stop and Remove the Windows Service
Write-Host "Removing Background Service..." -ForegroundColor Yellow
$serviceName = "BiometricUnlockService"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue

if ($existing) {
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    & sc.exe delete $serviceName | Out-Null
    Write-Host "Service removed successfully!" -ForegroundColor Green
} else {
    Write-Host "Service not found, skipping." -ForegroundColor Green
}

# 3. Remove Desktop & Start Menu Shortcuts
Write-Host "Removing App Shortcuts..." -ForegroundColor Yellow
$desktopPath = [System.Environment]::GetFolderPath('Desktop')
$startMenuPath = [System.Environment]::GetFolderPath('Programs')

$desktopShortcut = "$desktopPath\Biometric Setup.lnk"
if (Test-Path $desktopShortcut) { Remove-Item $desktopShortcut -Force }

$startMenuShortcut = "$startMenuPath\Biometric Setup.lnk"
if (Test-Path $startMenuShortcut) { Remove-Item $startMenuShortcut -Force }

Write-Host "Shortcuts removed!" -ForegroundColor Green

# 4. Clean DPAPI Vault (Optional - let's ask the user if they want to wipe data)
$response = Read-Host "Do you want to delete all saved credentials and paired devices? (Y/N)"
if ($response -match "^[Yy]$") {
    $vaultDir = "$env:ProgramData\BiometricUnlock"
    if (Test-Path $vaultDir) {
        Remove-Item -Path $vaultDir -Recurse -Force
        Write-Host "Credentials and pairing data deleted!" -ForegroundColor Green
    }
}

Write-Host "`n===============================================" -ForegroundColor Cyan
Write-Host "🗑️ UNINSTALLATION COMPLETE!" -ForegroundColor Green
Write-Host "===============================================" -ForegroundColor Cyan
