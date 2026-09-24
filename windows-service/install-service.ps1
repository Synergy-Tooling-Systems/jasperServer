#Requires -RunAsAdministrator
<#
Installs jasper-report-service as a Windows Service using WinSW.
Run this from an elevated PowerShell, from inside a prepared dist folder
(see build-dist.ps1) that contains:
  jasper-report-service.exe   (WinSW, downloaded separately)
  jasper-report-service.xml
  jasper-report-service.jar
  reports\
  config\db.properties
#>

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Test-Path ".\jasper-report-service.exe")) {
    Write-Error "jasper-report-service.exe not found. Download WinSW (https://github.com/winsw/winsw/releases), a .NET 4+ or .NET Core build, and rename it to jasper-report-service.exe in this folder."
    exit 1
}
if (-not (Test-Path ".\jasper-report-service.jar")) {
    Write-Error "jasper-report-service.jar not found. Run build-dist.ps1 from the project root first."
    exit 1
}

& ".\jasper-report-service.exe" install
Start-Service jasper-report-service
Get-Service jasper-report-service
