# DSH Mobile APK builder (pure ASCII, no Gradle)
# Toolchain: JDK 17 + Android SDK build-tools 34.0.0 + platform android-34
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1
#
# Required environment variables:
#   JAVA_HOME            - JDK 17 home
#   ANDROID_SDK_ROOT     - Android SDK root
#   DSH_KS_PASS          - keystore password (never commit it)
# Optional:
#   DSH_KS_PATH          - keystore path (default: ./dsh-release.keystore)

$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME)        { throw 'JAVA_HOME is not set' }
if (-not $env:ANDROID_SDK_ROOT) { throw 'ANDROID_SDK_ROOT is not set' }
if (-not $env:DSH_KS_PASS)      { throw 'DSH_KS_PASS is not set (keystore password)' }

$JAVA_HOME = $env:JAVA_HOME
$SDK       = $env:ANDROID_SDK_ROOT
$BT        = Join-Path $SDK 'build-tools\34.0.0'
$AJ        = Join-Path $SDK 'platforms\android-34\android.jar'
$PROJ      = $PSScriptRoot
$OUT       = Join-Path $PROJ 'build'
if ($env:DSH_KS_PATH) { $KS = $env:DSH_KS_PATH } else { $KS = Join-Path $PROJ 'dsh-release.keystore' }
$KSPASS    = $env:DSH_KS_PASS
$KEYALIAS  = if ($env:DSH_KS_ALIAS) { $env:DSH_KS_ALIAS } else { 'dsh' }
$VERSION   = '1.2.1'
$VCODE     = '7'

Write-Host '=== 0) prepare ===' -ForegroundColor Cyan
# d8.bat / apksigner.bat read JAVA_HOME from the environment.
$env:JAVA_HOME = $JAVA_HOME
$env:ANDROID_SDK_ROOT = $SDK
foreach ($d in @($OUT, (Join-Path $OUT 'res'), (Join-Path $OUT 'gen'), (Join-Path $OUT 'obj'), (Join-Path $OUT 'dex'))) {
  New-Item -ItemType Directory -Force -Path $d | Out-Null
}
Get-ChildItem $OUT -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host '=== 1) aapt2 compile ===' -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir (Join-Path $PROJ 'res') -o (Join-Path $OUT 'res\compiled.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '=== 2) aapt2 link ===' -ForegroundColor Cyan
& "$BT\aapt2.exe" link `
  -o (Join-Path $OUT 'base.apk') `
  -I $AJ `
  --manifest (Join-Path $PROJ 'AndroidManifest.xml') `
  --java (Join-Path $OUT 'gen') `
  --min-sdk-version 24 --target-sdk-version 34 `
  --version-code $VCODE --version-name $VERSION `
  (Join-Path $OUT 'res\compiled.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '=== 3) javac ===' -ForegroundColor Cyan
$javas = @(Get-ChildItem (Join-Path $PROJ 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javas += (Join-Path $OUT 'gen\com\dsh\mobile\R.java')
& "$JAVA_HOME\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -nowarn -classpath $AJ -d (Join-Path $OUT 'obj') @javas
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '=== 4) d8 ===' -ForegroundColor Cyan
$classes = @(Get-ChildItem (Join-Path $OUT 'obj') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$d8args = @('--release', '--lib', $AJ, '--min-api', '24', '--output', (Join-Path $OUT 'dex')) + $classes
& "$BT\d8.bat" @d8args
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '=== 5) inject classes.dex ===' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$apk = Join-Path $OUT 'base.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apk, [System.IO.Compression.ZipArchiveMode]::Update)
$old = $zip.GetEntry('classes.dex')
if ($old -ne $null) { $old.Delete() }
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$src = [System.IO.File]::OpenRead((Join-Path $OUT 'dex\classes.dex'))
$dst = $entry.Open()
$src.CopyTo($dst)
$dst.Dispose(); $src.Dispose(); $zip.Dispose()

Write-Host '=== 6) zipalign ===' -ForegroundColor Cyan
& "$BT\zipalign.exe" -f -p 4 $apk (Join-Path $OUT 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '=== 7) sign ===' -ForegroundColor Cyan
if (-not (Test-Path $KS)) { throw "keystore missing: $KS" }
$final = Join-Path $OUT 'DSH-mobile.apk'
& "$BT\apksigner.bat" sign --ks $KS --ks-pass "pass:$KSPASS" --ks-key-alias $KEYALIAS --key-pass "pass:$KSPASS" --out $final (Join-Path $OUT 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Host '=== 8) verify ===' -ForegroundColor Cyan
& "$BT\apksigner.bat" verify -v $final

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
$hash = (Get-FileHash $final -Algorithm SHA256).Hash
Write-Host ''
Write-Host "OK  $final" -ForegroundColor Green
Write-Host "    size : $size KB"
Write-Host "    sha256: $hash"

# Also drop a copy at the project root for easy pickup.
Copy-Item $final (Join-Path $PROJ 'DSH-mobile.apk') -Force
Write-Host "    copy : $(Join-Path $PROJ 'DSH-mobile.apk')"