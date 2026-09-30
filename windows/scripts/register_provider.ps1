# Run this script as Administrator
[CmdletBinding()]
param()

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Please run this PowerShell script as Administrator!"
    Exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$dllPath = Join-Path $scriptDir "..\CredentialProvider\bin\x64\Release\FingerprintCredentialProvider.dll"
$dllPath = [System.IO.Path]::GetFullPath($dllPath)

if (-not (Test-Path $dllPath)) {
    Write-Error "DLL not found at: $dllPath. Please compile the solution first!"
    Exit 1
}

Write-Host "Registering Credential Provider COM DLL: $dllPath" -ForegroundColor Cyan
& regsvr32.exe /s "$dllPath"

Write-Host "[OK] Fingerprint Credential Provider registered successfully!" -ForegroundColor Green
Write-Host "You will see 'Phone Fingerprint Unlock' tile on your lock screen (Win + L)." -ForegroundColor Yellow