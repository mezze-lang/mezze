# The Mezze toolchain installer for Windows.
#
#   irm https://github.com/mezze-lang/mezze/releases/latest/download/install.ps1 | iex
#
# Downloads the Windows release and unpacks the toolchain into %USERPROFILE%\.mezze,
# then adds it to your user PATH. `mez check`/`fmt`/`init` work immediately (no JVM);
# `mez dev`/`build` light up once a GraalVM is installed.
#
# Env knobs:
#   $env:MEZ_VERSION   install a specific version (e.g. 0.1.0) instead of the latest
#   $env:MEZZE_HOME    install location (default: %USERPROFILE%\.mezze)
#   $env:MEZ_REPO      owner/repo to fetch from (default: mezze-lang/mezze)
#   $env:GITHUB_TOKEN  token for a PRIVATE repo's API + asset downloads
$ErrorActionPreference = 'Stop'

$repo = if ($env:MEZ_REPO) { $env:MEZ_REPO } else { 'mezze-lang/mezze' }
$dest = if ($env:MEZZE_HOME) { $env:MEZZE_HOME } else { Join-Path $env:USERPROFILE '.mezze' }

function Step($m) { Write-Host "  > $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  + $m" -ForegroundColor Green }
function Fail($msg, $hint) {
  Write-Host "`n  x $msg" -ForegroundColor Red
  if ($hint) { Write-Host "    $hint" -ForegroundColor DarkGray }
  Write-Host ""
  exit 1
}

Write-Host ""
Write-Host "  mez " -ForegroundColor Magenta -NoNewline
Write-Host "- the Mezze toolchain installer"
Write-Host "  --------------------------------------`n" -ForegroundColor DarkGray

# ---- detect platform ---------------------------------------------------------
Step "Detecting your platform"
$arch = $env:PROCESSOR_ARCHITECTURE
switch ($arch) {
  'AMD64' { $plat = 'windows-x64' }
  default {
    Fail "unsupported CPU '$arch' - only windows-x64 is built today." `
         "On ARM Windows, run the x64 build under emulation, or open an issue at https://github.com/$repo"
  }
}
Ok "Platform  $plat"

# Optional auth for a private repo.
$headers = @{}
if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $($env:GITHUB_TOKEN)" }

# ---- resolve version ---------------------------------------------------------
Step "Resolving the release"
try {
  if ($env:MEZ_VERSION) {
    $ver = $env:MEZ_VERSION; $tag = "v$ver"
  } else {
    $rel = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/latest"
    $tag = $rel.tag_name; $ver = $tag -replace '^v',''
  }
} catch {
  Fail "couldn't resolve the latest release of $repo." `
       "Is the repo public? For a private repo set `$env:GITHUB_TOKEN. Check your network, then re-run."
}
if (-not $ver) { Fail "no version found for $repo." "See https://github.com/$repo/releases" }
Ok "Version   $ver  (from $repo)"

$asset = "mez-$ver-$plat.zip"
$url = "https://github.com/$repo/releases/download/$tag/$asset"

# ---- download ----------------------------------------------------------------
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
  Step "Downloading $asset"
  $zip = Join-Path $tmp $asset
  Invoke-WebRequest -Headers $headers -Uri $url -OutFile $zip
  Ok ("Downloaded  {0:N1} MB" -f ((Get-Item $zip).Length / 1MB))

  # ---- install ---------------------------------------------------------------
  Step "Installing into $dest"
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  Expand-Archive -Path $zip -DestinationPath $dest -Force
  $mez = Join-Path $dest 'bin\mez.exe'
  if (-not (Test-Path $mez)) {
    Fail "install looks incomplete - $mez is missing." "The archive may be corrupt; re-run the installer."
  }
  $installed = (& $mez --version 2>$null | Select-Object -First 1)
  Ok "Installed  $installed"
} catch {
  Fail "download or unpack failed." `
       "No $plat build in $tag? See https://github.com/$repo/releases, or set `$env:MEZ_VERSION. ($url)"
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# ---- PATH (user scope) -------------------------------------------------------
$bindir = Join-Path $dest 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
Write-Host ""
if (($userPath -split ';') -contains $bindir) {
  Write-Host "  + mez is ready." -ForegroundColor Green -NoNewline
  Write-Host "  Open a new terminal and run: mez --version"
} else {
  [Environment]::SetEnvironmentVariable('Path', "$bindir;$userPath", 'User')
  $env:Path = "$bindir;$env:Path"   # current session too
  Write-Host "  + Done. Added mez to your user PATH." -ForegroundColor Green
  Write-Host "    Open a new terminal (so PATH refreshes), then run: " -NoNewline
  Write-Host "mez --version" -ForegroundColor White
}
Write-Host "`n  Docs & getting started: https://github.com/$repo`n" -ForegroundColor DarkGray
