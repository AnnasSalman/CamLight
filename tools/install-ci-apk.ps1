<#
.SYNOPSIS
  Downloads the APK from the latest successful GitHub build and installs it on every connected phone.

.DESCRIPTION
  Needs the GitHub CLI (gh) logged in to the account that owns the repository, and adb with USB debugging
  authorised on the phones. The repository owner is taken from the git remote; its gh login is used for the
  download without changing the active gh account.

.EXAMPLE
  .\tools\install-ci-apk.ps1
  .\tools\install-ci-apk.ps1 -Serial 7de6t8tsmznf9ts8
#>
param(
    [string]$Adb = "adb",
    [string]$Serial = "",
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    $remote = git remote get-url origin
    if ($remote -notmatch "github\.com[:/](?<owner>[^/]+)/(?<name>[^/.]+)") { throw "Could not read the GitHub repository from the git remote: $remote" }
    $owner = $Matches.owner
    $repo = "$owner/$($Matches.name)"

    # Use the owner's own gh login for this process only.
    $token = gh auth token -u $owner 2>$null
    if ($token) { $env:GH_TOKEN = $token } else { Write-Warning "No gh login found for '$owner'; using the active account." }

    Write-Host "Latest successful build of $repo ($Branch)..."
    $runId = gh run list -R $repo --workflow build.yml --branch $Branch --status success --limit 1 --json databaseId --jq ".[0].databaseId"
    if (-not $runId) { throw "No successful build found yet. Push a commit and wait for the Build workflow to finish." }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("camlight-ci-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    gh run download $runId -R $repo -n camlight-debug-apk -D $tmp
    $apk = Join-Path $tmp "app-debug.apk"
    if (-not (Test-Path $apk)) { throw "The build did not contain app-debug.apk." }
    Write-Host "Downloaded build $runId ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB)."

    $devices = @()
    if ($Serial) { $devices = @($Serial) }
    else {
        $devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] }
    }
    if (-not $devices) { throw "No phone found over adb. Check the cable, USB debugging and 'Install via USB'." }

    foreach ($d in $devices) {
        $model = & $Adb -s $d shell getprop ro.product.model
        Write-Host "Installing on $d ($model)..."
        & $Adb -s $d install -r $apk
    }
}
finally {
    Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue
    if ($tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    Pop-Location
}
