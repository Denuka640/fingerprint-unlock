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
    Write-Error "Service executable not found at: $exePath. Please build the service first!"
    Exit 1
}

$serviceName = "BiometricUnlockService"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue

if ($existing) {
    Write-Host "Stopping existing service..." -ForegroundColor Yellow
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    & sc.exe delete $serviceName
    Start-Sleep -Seconds 1
}

Write-Host "Creating Windows Service '$serviceName'..." -ForegroundColor Cyan
& sc.exe create $serviceName binPath= "`"$exePath`" --service" start= auto DisplayName= "Biometric Phone Unlock Service"
& sc.exe start $serviceName

Write-Host "✓ Windows Service installed and started successfully!" -ForegroundColor Green
