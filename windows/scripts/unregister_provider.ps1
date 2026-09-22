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

if (Test-Path $dllPath) {
    Write-Host "Unregistering Credential Provider COM DLL: $dllPath" -ForegroundColor Cyan
    & regsvr32.exe /u /s "$dllPath"
}

# Clean registry keys if needed
Remove-Item -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}" -Force -ErrorAction SilentlyContinue
Remove-Item -Path "HKCR:\CLSID\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "✓ Fingerprint Credential Provider unregistered successfully." -ForegroundColor Green
