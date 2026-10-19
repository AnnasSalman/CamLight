<#
.SYNOPSIS
  Serves the debug APK on the local network so a phone can download and install it without a USB cable.

.DESCRIPTION
  Copies the APK to a temporary folder (so nothing else is exposed), starts a small HTTP server and prints the
  address to open in the phone's browser. Press Ctrl+C to stop; the temporary folder is removed.
  Anyone on the same network can download the file while it runs, so stop it when you are done.

.EXAMPLE
  .\tools\serve-apk.ps1
  .\tools\serve-apk.ps1 -Apk C:\Downloads\app-debug.apk -Port 8001
#>
param(
    [string]$Apk = (Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"),
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (build it first with .\gradlew.bat assembleDebug)" }

$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) { throw "Python was not found on PATH." }

$ip = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" -or $_.IPAddress -match "^172\.(1[6-9]|2\d|3[01])\." } |
    Select-Object -First 1).IPAddress
if (-not $ip) { $ip = "<this PC's IP address>" }

$dir = Join-Path ([IO.Path]::GetTempPath()) ("camlight-apk-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $dir | Out-Null
Copy-Item $Apk (Join-Path $dir "CamLight.apk")

try {
    Write-Host ""
    Write-Host "On the phone (same Wi-Fi), open:  http://${ip}:$Port/CamLight.apk" -ForegroundColor Green
    Write-Host "Install it, allowing installs from the browser if asked. Press Ctrl+C here when done."
    Write-Host ""
    & $python -I -m http.server $Port --bind 0.0.0.0 --directory $dir
}
finally {
    Remove-Item -Recurse -Force $dir -ErrorAction SilentlyContinue
}
