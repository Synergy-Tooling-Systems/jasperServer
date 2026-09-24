#Requires -RunAsAdministrator
<#
Stops and removes the jasper-report-service Windows Service.
Run this from an elevated PowerShell in the folder containing
jasper-report-service.exe / .xml.
#>

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

Stop-Service jasper-report-service -ErrorAction SilentlyContinue
& ".\jasper-report-service.exe" uninstall
