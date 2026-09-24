<#
Builds jasper-report-service and assembles a self-contained distribution
folder under .\dist ready to copy to a server and install as a Windows
Service (see windows-service\README.md).

Usage: .\build-dist.ps1
#>

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

Write-Host "Building jar..." -ForegroundColor Cyan
& "$root\mvnw.cmd" clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$jar = Get-ChildItem "$root\target\jasper-report-service-*.jar" |
    Where-Object { $_.Name -notlike "*.jar.original" } |
    Select-Object -First 1
if (-not $jar) { throw "Built jar not found under target\" }

$distDir = "$root\dist"
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Path $distDir | Out-Null
New-Item -ItemType Directory -Path "$distDir\reports" | Out-Null
New-Item -ItemType Directory -Path "$distDir\config" | Out-Null

Copy-Item $jar.FullName "$distDir\jasper-report-service.jar"
Copy-Item "$root\reports\*" "$distDir\reports\" -Recurse -Force
Copy-Item "$root\config\db.properties.example" "$distDir\config\db.properties.example"
Copy-Item "$root\config\service.properties.example" "$distDir\config\service.properties.example"
Copy-Item "$root\windows-service\jasper-report-service.xml" "$distDir\jasper-report-service.xml"
Copy-Item "$root\windows-service\install-service.ps1" "$distDir\install-service.ps1"
Copy-Item "$root\windows-service\uninstall-service.ps1" "$distDir\uninstall-service.ps1"
Copy-Item "$root\windows-service\README.txt" "$distDir\README.txt"

Write-Host ""
Write-Host "Distribution assembled at $distDir" -ForegroundColor Green
Write-Host "Next steps on the target server:" -ForegroundColor Green
Write-Host "  1. Copy the dist folder there."
Write-Host "  2. Copy config\db.properties.example to config\db.properties and fill in real credentials."
Write-Host "     Copy config\service.properties.example to config\service.properties to change the API port."
Write-Host "  3. Download WinSW from https://github.com/winsw/winsw/releases and save it as jasper-report-service.exe in this folder."
Write-Host "  4. From an elevated PowerShell in that folder, run .\install-service.ps1"
