# Builds the module APK without Gradle and without the Android SDK manager.
#
# Only two things are needed: a JDK 17 and an Android SDK containing
# platforms/android-<n>/android.jar plus build-tools (aapt2, d8, apksigner,
# zipalign). Anything missing is downloaded into .buildtools/ next to this
# script, so the build never depends on machine-wide configuration.
#
# Usage:  pwsh -File build.ps1
#         pwsh -File build.ps1 -ApiLevel 36 -SkipSign

[CmdletBinding()]
param(
    [int]$ApiLevel = 35,
    [string]$BuildToolsVersion = '35.0.0',
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$SkipSign,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'build'
$tools = Join-Path $root '.buildtools'
$libs = Join-Path $root 'libs'

function Write-Step($text) { Write-Host "== $text ==" -ForegroundColor Cyan }
function Write-Info($text) { Write-Host "   $text" -ForegroundColor Gray }
function Fail($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force -Path $out, $tools, $libs | Out-Null

# ---------------------------------------------------------------- downloads
#
# Downloads go through Node rather than Invoke-WebRequest: in the restricted
# shell this project is built from, PowerShell's TLS stack (schannel) fails the
# handshake with SEC_E_NO_CREDENTIALS for every https:// URL, while Node's
# bundled OpenSSL works. tools/fetch.js is the downloader.
$node = (Get-Command node -ErrorAction SilentlyContinue).Source

function Get-RemoteFile($url, $target) {
    if (-not $node) { Fail "node is required to download $url (install Node.js or pass -AndroidSdk)" }
    & $node (Join-Path $root 'tools/fetch.js') $url $target
    return ($LASTEXITCODE -eq 0 -and (Test-Path $target))
}

# Google re-cuts the platform archives as _r01, _r02, ... without keeping the
# old names, so every spelling is tried before giving up.
function Get-GoogleArchive($names, $target) {
    foreach ($name in $names) {
        Write-Info "trying $name"
        if (Get-RemoteFile "https://dl.google.com/android/repository/$name" $target) {
            Write-Info "got $name"
            return $true
        }
        Write-Info '  not available'
    }
    return $false
}

# ---------------------------------------------------------------- 1. the JDK

Write-Step '1/7 locating a JDK'
$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome 'bin/javac.exe'))) {
    Write-Info "JAVA_HOME: $javaHome"
} else {
    $javaHome = $null
    # Prefer a real JDK 17+; d8 needs at least Java 11.
    $candidates = @()
    foreach ($base in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
                        "$env:ProgramFiles\Microsoft\jdk", "$env:LOCALAPPDATA\Programs\Java")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin/javac.exe') }
        }
    }
    $picked = $candidates |
        Where-Object { $_.Name -match 'jdk-?(17|18|19|2[0-9])' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $picked) {
        $picked = $candidates | Sort-Object Name -Descending | Select-Object -First 1
    }
    if ($picked) {
        $javaHome = $picked.FullName
        Write-Info "found $javaHome"
    }
}
if (-not $javaHome) { Fail 'no JDK found. Install one (e.g. choco install temurin17) and retry.' }
$javac = Join-Path $javaHome 'bin/javac.exe'
$keytool = Join-Path $javaHome 'bin/keytool.exe'
$java = Join-Path $javaHome 'bin/java.exe'
& $javac -version 2>&1 | ForEach-Object { Write-Info $_ }

# ------------------------------------------------------------- 2. android.jar

Write-Step '2/7 resolving android.jar'
$sdkRoot = Join-Path $tools 'sdk'
$androidJar = $null
$platforms = @()
if ($AndroidSdk) { $platforms += (Join-Path $AndroidSdk "platforms\android-$ApiLevel") }
$platforms += (Join-Path $sdkRoot "platforms\android-$ApiLevel")
foreach ($platform in $platforms) {
    $candidate = Join-Path $platform 'android.jar'
    if (Test-Path $candidate) { $androidJar = $candidate; break }
}
if (-not $androidJar) {
    # A previous build already downloaded and unpacked it; the platform archive
    # unpacks to android-<n>/ rather than platforms/android-<n>/, so anything
    # left over at the SDK root is accepted as is.
    $found = Get-ChildItem $sdkRoot -Recurse -Filter 'android.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($found) { $androidJar = $found.FullName }
}
if (-not $androidJar) {
    $zip = Join-Path $tools 'android-platform.zip'
    Write-Info "downloading android-$ApiLevel from dl.google.com ..."
    New-Item -ItemType Directory -Force -Path $sdkRoot | Out-Null
    $platformZips = @(
        "platform-$ApiLevel`_r02.zip",
        "platform-$ApiLevel`_r01.zip",
        "platform-$ApiLevel`_r03.zip"
    )
    if (-not (Get-GoogleArchive $platformZips $zip)) {
        Fail ('could not download android.jar automatically. Download it and place it at ' +
              (Join-Path $sdkRoot "platforms\android-$ApiLevel\android.jar"))
    }
    Expand-Archive -Path $zip -DestinationPath $sdkRoot -Force
    $found = Get-ChildItem $sdkRoot -Recurse -Filter 'android.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($found) {
        $androidJar = $found.FullName
        # Tidy the layout so the next build finds it in one step.
        $target = Join-Path $sdkRoot "platforms\android-$ApiLevel"
        if ($found.Directory.FullName -ne $target) {
            New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
            if (Test-Path $target) { Remove-Item $target -Recurse -Force }
            Move-Item $found.Directory.FullName $target
            $androidJar = Join-Path $target 'android.jar'
        }
    }
}
if (-not $androidJar) { Fail 'android.jar not found.' }
Write-Info $androidJar

# ----------------------------------------------------------- 3. build-tools

Write-Step '3/7 resolving build-tools (aapt2, d8, apksigner, zipalign)'
$btDir = $null
$btCandidates = @()
if ($AndroidSdk) { $btCandidates += (Join-Path $AndroidSdk "build-tools\$BuildToolsVersion") }
$btCandidates += (Join-Path $tools "sdk\build-tools\$BuildToolsVersion")
foreach ($dir in $btCandidates) {
    if (Test-Path (Join-Path $dir 'aapt2.exe')) { $btDir = $dir; break }
}
if (-not $btDir) {
    $found = Get-ChildItem (Join-Path $tools 'sdk\build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $btDir = $found.FullName }
}
if (-not $btDir) {
    # The published file names mix a full "35.0.0" with a short "35" and use
    # either "-" or "_" before the host, so every plausible spelling is probed
    # rather than assumed.
    $short = ($BuildToolsVersion -split '\.')[0]
    $zipNames = @(
        "build-tools_r$BuildToolsVersion-windows.zip",
        "build-tools_r$BuildToolsVersion`_windows.zip",
        "build-tools_r$BuildToolsVersion.zip",
        "build-tools_r$short-windows.zip",
        "build-tools_r$short`_windows.zip",
        "build-tools_r$short.zip"
    ) | Select-Object -Unique

    $zip = Join-Path $tools "build-tools-$BuildToolsVersion.zip"
    if (-not (Get-GoogleArchive $zipNames $zip)) {
        Fail ("could not find a build-tools archive for $BuildToolsVersion. " +
              'Install the Android SDK build-tools and pass -AndroidSdk <sdk path>, ' +
              'or set -BuildToolsVersion to a version you already have.')
    }
    Write-Info "got $((Get-Item $zip).Name)"

    $tmp = Join-Path $tools 'bt'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # The archive holds android-<version>/; move it into the SDK layout.
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    $target = Join-Path $tools "sdk\build-tools\$BuildToolsVersion"
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Move-Item $inner.FullName $target
    $btDir = $target
    # The extracted folder name does not always equal the version asked for.
    if (-not (Test-Path (Join-Path $btDir 'aapt2.exe'))) {
        $found = Get-ChildItem $btDir -Recurse -Filter 'aapt2.exe' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { $btDir = $found.Directory.FullName }
    }
}
$aapt2 = Join-Path $btDir 'aapt2.exe'
$zipalign = Join-Path $btDir 'zipalign.exe'
$apksigner = Join-Path $btDir 'apksigner.bat'
if (-not (Test-Path $apksigner)) {
    $apksigner = Join-Path $btDir 'apksigner.jar'
}
foreach ($tool in @($aapt2, $zipalign)) {
    if (-not (Test-Path $tool)) { Fail "missing $tool" }
}
Write-Info $btDir

# ------------------------------------------------------------------ 4. javac

Write-Step '4/7 compiling java'
$classes = Join-Path $out 'classes'
if ($Rebuild -and (Test-Path $classes)) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
$sources += Get-ChildItem (Join-Path $root 'stubs') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
$sourcesFile = Join-Path $out 'sources.txt'
Set-Content -Path $sourcesFile -Value $sources -Encoding ASCII
Write-Info "$($sources.Count) source files"

# -Xlint:-deprecation silences javac's "uses a deprecated API" note, which
# would otherwise land on stderr and, once redirected, abort the build.
#
# aapt2 link is what writes R.java, and the settings screen refers to it, so the
# resources are linked once here, before javac, with the generated sources on the
# source path. The APK this writes is a throwaway: step 5 links again, into the
# APK that is actually signed.
$genDir = Join-Path $out 'gen'
if (Test-Path $genDir) { Remove-Item $genDir -Recurse -Force }
$earlyFlat = Join-Path $out 'res-flat'
if (Test-Path $earlyFlat) { Remove-Item $earlyFlat -Recurse -Force }
New-Item -ItemType Directory -Force -Path $earlyFlat | Out-Null
$resDir = Join-Path $root 'res'
if (Test-Path $resDir) {
    & $aapt2 compile --dir $resDir -o $earlyFlat
    if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile failed' }
}
$earlyArgs = @('link', '-o', (Join-Path $out 'module.early.apk'),
    '--manifest', (Join-Path $root 'AndroidManifest.xml'),
    '-I', $androidJar,
    '--java', $genDir,
    '--min-sdk-version', '29',
    '--target-sdk-version', '34')
$earlyFiles = Get-ChildItem $earlyFlat -Filter '*.flat' -ErrorAction SilentlyContinue
if ($earlyFiles) { $earlyArgs += $earlyFiles.FullName }
& $aapt2 @earlyArgs
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link failed while generating R.java' }

$javacLog = Join-Path $out 'javac.log'
$ErrorActionPreference = 'Continue'
& $javac -classpath $androidJar -sourcepath $genDir -source 8 -target 8 -encoding UTF-8 -nowarn `
    -Xlint:-deprecation -d $classes "@$sourcesFile" *> $javacLog
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($compileExit -ne 0) {
    Get-Content $javacLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Info $_ }
    Fail 'javac failed'
}
$classCount = (Get-ChildItem $classes -Recurse -Filter '*.class').Count
Write-Info "$classCount classes"

# --------------------------------------------------------------- 5. aapt2/dex

Write-Step '5/7 packaging resources and dex'
$flat = Join-Path $out 'res-flat'
if (Test-Path $flat) { Remove-Item $flat -Recurse -Force }
New-Item -ItemType Directory -Force -Path $flat | Out-Null
$resDir = Join-Path $root 'res'
if (Test-Path $resDir) {
    & $aapt2 compile --dir $resDir -o $flat
    if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile failed' }
}

$unsigned = Join-Path $out 'module.unsigned.apk'
if (Test-Path $unsigned) { Remove-Item $unsigned -Force }
$linkArgs = @('link', '-o', $unsigned,
    '--manifest', (Join-Path $root 'AndroidManifest.xml'),
    '-I', $androidJar,
    '--java', (Join-Path $out 'gen'),
    '--min-sdk-version', '29',
    '--target-sdk-version', '34')
$flatFiles = Get-ChildItem $flat -Filter '*.flat' -ErrorAction SilentlyContinue
if ($flatFiles) { $linkArgs += $flatFiles.FullName }
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link failed' }

# d8 ships as a jar inside build-tools; the .bat wrapper is not always present.
$d8 = Join-Path $btDir 'd8.bat'
$d8Jar = Join-Path $btDir 'lib/d8.jar'
$dexDir = Join-Path $out 'dex'
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

# The Xposed API stubs must NOT be dexed: the framework provides them at
# runtime and refuses modules that bundle them.
$classList = Join-Path $out 'classlist.txt'
Get-ChildItem $classes -Recurse -Filter '*.class' |
    Where-Object { $_.FullName -notmatch '\\de\\robv\\' } |
    Select-Object -ExpandProperty FullName |
    Set-Content -Path $classList -Encoding ASCII
Write-Info "dexing $((Get-Content $classList).Count) classes"

if (Test-Path $d8) {
    & $d8 --release --min-api 29 --lib $androidJar --output $dexDir "@$classList"
    if ($LASTEXITCODE -ne 0) { Fail 'd8 failed' }
} elseif (Test-Path $d8Jar) {
    & $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 29 `
        --lib $androidJar --output $dexDir "@$classList"
    if ($LASTEXITCODE -ne 0) { Fail 'd8 (jar) failed' }
} else {
    Fail "no d8 found in $btDir"
}
if (-not (Test-Path (Join-Path $dexDir 'classes.dex'))) { Fail 'classes.dex was not produced' }

# ---------------------------------------------------- 6. add assets and dex

Write-Step '6/7 adding assets and dex to the apk'
# aapt2 dropped the old `aapt add` subcommand, so the dex and the assets are
# appended as plain zip entries. .NET's ZipArchive in Update mode rewrites the
# container while copying the linked entries through unchanged, which matters:
# resources.arsc must stay stored and uncompressed. zipalign fixes the
# alignment that the rewrite loses.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Add-ZipEntries($apk, $pairs) {
    $mode = [System.IO.Compression.ZipArchiveMode]::Update
    $zip = [System.IO.Compression.ZipFile]::Open($apk, $mode)
    try {
        foreach ($pair in $pairs) {
            $entryName = $pair[1]
            $existing = $zip.GetEntry($entryName)
            if ($existing) { $existing.Delete() }
            $level = [System.IO.Compression.CompressionLevel]::NoCompression
            $entry = $zip.CreateEntry($entryName, $level)
            $stream = $entry.Open()
            try {
                $bytes = [System.IO.File]::ReadAllBytes($pair[0])
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
}

$entries = @(, @((Join-Path $dexDir 'classes.dex'), 'classes.dex'))
$assetsDir = Join-Path $root 'assets'
if (Test-Path $assetsDir) {
    $assetsDir = (Resolve-Path $assetsDir).Path
    foreach ($file in (Get-ChildItem $assetsDir -Recurse -File)) {
        # The "assets/" prefix is part of the entry name: LSPosed looks for
        # assets/xposed_init, and a file sitting at the apk root is simply not
        # found (the module then silently never loads).
        $relative = $file.FullName.Substring($assetsDir.Length + 1) -replace '\\', '/'
        $entries += , @($file.FullName, "assets/$relative")
    }
}
Add-ZipEntries $unsigned $entries
Write-Info "added $($entries.Count) entries to the apk"

$aligned = Join-Path $out 'module.aligned.apk'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }
& $zipalign -c -p 4 $aligned | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'the apk is not zip aligned' }

# ------------------------------------------------------------------ 7. sign

$apk = Join-Path $out 'NoteExport.apk'
if ($SkipSign) {
    $apk = $aligned
    Write-Step '7/7 skipping signing (-SkipSign)'
} else {
    Write-Step '7/7 signing'
    $keystore = Join-Path $root 'keystore/note-export.keystore'
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
        Write-Info 'generating a keystore (kept out of build/ so it survives clean builds)'
        # keytool chats on stderr, and under the script's Stop preference that
        # chatter would abort the build, so its streams go to a log file and only
        # the exit code decides.
        $log = Join-Path $out 'keytool.log'
        $ErrorActionPreference = 'Continue'
        & $keytool -genkeypair -keystore $keystore -alias noteexport `
            -storepass noteexport -keypass noteexport -keyalg RSA -keysize 2048 `
            -validity 10000 -dname 'CN=NoteExport' *> $log
        $keytoolExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($keytoolExit -ne 0) {
            Get-Content $log -ErrorAction SilentlyContinue | ForEach-Object { Write-Info $_ }
            Fail 'keytool failed'
        }
    }
    if (Test-Path $apk) { Remove-Item $apk -Force }
    $ErrorActionPreference = 'Continue'
    if ($apksigner.EndsWith('.jar')) {
        & $java -jar $apksigner sign --ks $keystore --ks-pass pass:noteexport `
            --key-pass pass:noteexport --out $apk $aligned
    } else {
        & $apksigner sign --ks $keystore --ks-pass pass:noteexport `
            --key-pass pass:noteexport --out $apk $aligned
    }
    $signExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($signExit -ne 0) { Fail 'apksigner failed' }

    # Last sanity check: a signature that does not verify would only fail on the
    # device, where it is far more annoying to diagnose.
    $ErrorActionPreference = 'Continue'
    if ($apksigner.EndsWith('.jar')) {
        & $java -jar $apksigner verify --print-certs $apk *> (Join-Path $out 'apksigner.log')
    } else {
        & $apksigner verify --print-certs $apk *> (Join-Path $out 'apksigner.log')
    }
    $verifyExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($verifyExit -ne 0) {
        Get-Content (Join-Path $out 'apksigner.log') -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Info $_ }
        Fail 'the apk signature did not verify'
    }
    Write-Info 'signature verified'
}

Write-Host ''
Write-Host "DONE -> $apk" -ForegroundColor Green
Write-Host "size: $([math]::Round((Get-Item $apk).Length / 1KB, 1)) KB"
Write-Host ''
Write-Host 'Install with:' -ForegroundColor Yellow
Write-Host "  adb install -r `"$apk`""
