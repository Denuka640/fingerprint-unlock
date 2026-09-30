# Run this script as Administrator
[CmdletBinding()]
param()

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Please run this PowerShell script as Administrator!"
    Exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$exePath = Join-Path $scriptDir "..\Service\bin\Release\net10.0-windows10.0.19041.0\BiometricUnlockService.exe"
$exePath = [System.IO.Path]::GetFullPath($exePath)

if (-not (Test-Path $exePath)) {
    Write-Error "Service EXE not found at:`n  $exePath`nPlease build the Service project first (dotnet build -c Release)."
    Exit 1
}

$serviceName = "BiometricUnlockService"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue

if ($existing) {
    Write-Host "Stopping and removing existing service..." -ForegroundColor Yellow
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    & sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}

Write-Host "Installing Windows Service '$serviceName'..." -ForegroundColor Cyan
Write-Host "  Path: $exePath"

# Use New-Service (more reliable than sc.exe in PowerShell)
New-Service -Name $serviceName `
            -BinaryPathName "`"$exePath`" --service" `
            -DisplayName "Biometric Phone Unlock Service" `
            -Description "Manages BLE GATT advertising and biometric credential verification for phone-based Windows unlock." `
            -StartupType Automatic `
            -ErrorAction Stop | Out-Null

Write-Host "Starting service..." -ForegroundColor Cyan
Start-Service -Name $serviceName -ErrorAction Stop

$svc = Get-Service -Name $serviceName
Write-Host "[OK] Service '$serviceName' is now: $($svc.Status)" -ForegroundColor Green
Write-Host ""
Write-Host "Next step: run .\register_provider.ps1 (as Admin) to register the lock screen tile." -ForegroundColor Yellow