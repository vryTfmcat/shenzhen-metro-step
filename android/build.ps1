# Build and sign a local APK from the same web assets as the desktop app.
$ErrorActionPreference = 'Stop'
$worktree = Split-Path -Parent $PSScriptRoot
$project = Split-Path -Parent $worktree
$sdk = Join-Path $project 'tools\android-sdk'
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Recurse -Filter aapt2.exe |
    Select-Object -First 1 -ExpandProperty DirectoryName
$androidJar = Join-Path $sdk 'platform\android-35\android.jar'
if (!$buildTools -or !(Test-Path -LiteralPath $androidJar)) { throw '缺少 Android 35 SDK 或 Build Tools 35.0.0。' }
if (!$env:JAVA_HOME) { throw '请将 JAVA_HOME 指向 JDK 11 或更新版本。' }

function Invoke-BuildTool([string] $file, [string[]] $toolArgs) {
    & $file @toolArgs
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($file)) failed ($LASTEXITCODE)" }
}

$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$config = [xml][IO.File]::ReadAllText($manifest)
$version = $config.DocumentElement.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$release = Join-Path $project 'releases'
$private = Join-Path $project 'private-data\android-signing'
$build = [IO.Directory]::CreateTempSubdirectory('metro-apk-').FullName
$classes = Join-Path $build 'classes'
$assets = Join-Path $build 'assets'
New-Item -ItemType Directory -Force -Path $classes, $assets, $release, $private | Out-Null
# aapt2 on Windows requires an ASCII staging path for local resource files.
$stagedJar = Join-Path $build 'android.jar'
$stagedManifest = Join-Path $build 'AndroidManifest.xml'
Copy-Item -LiteralPath $androidJar -Destination $stagedJar
Copy-Item -LiteralPath $manifest -Destination $stagedManifest
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res') -Destination $build -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'src') -Destination $build -Recurse
foreach ($name in 'index.html', 'style.css', 'app.js') {
    Copy-Item -LiteralPath (Join-Path $worktree $name) -Destination $assets
}

$aapt2 = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$resources = Join-Path $build 'resources.zip'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$signed = Join-Path $build 'signed.apk'

Invoke-BuildTool $aapt2 @('compile', '--dir', (Join-Path $build 'res'), '-o', $resources)
Invoke-BuildTool $aapt2 @('link', '-I', $stagedJar, '--manifest', $stagedManifest, '-A', $assets, '-o', $unsigned, $resources)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $build 'src') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
Invoke-BuildTool $javac (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $stagedJar, '-d', $classes) + $sources)
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-BuildTool $java (@('-cp', (Join-Path $buildTools 'lib\d8.jar'), 'com.android.tools.r8.D8',
    '--release', '--min-api', '26', '--lib', $stagedJar, '--output', $build) + $classFiles)

$zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build 'classes.dex'), 'classes.dex') | Out-Null
} finally {
    $zip.Dispose()
}
Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)

$keystore = Join-Path $private 'metrostep.p12'
$protectedPassword = Join-Path $private 'password.dpapi'
$signer = Join-Path $buildTools 'lib\apksigner.jar'
try {
    if (Test-Path -LiteralPath $protectedPassword) {
        $secret = (Get-Content -LiteralPath $protectedPassword -Raw).Trim() | ConvertTo-SecureString
        $env:METRO_SIGNING_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    } elseif (Test-Path -LiteralPath $keystore) {
        throw '已存在签名密钥，但缺少受保护的密码。'
    } else {
        $entropy = [byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($entropy)
        $env:METRO_SIGNING_PASSWORD = [Convert]::ToBase64String($entropy)
        ConvertTo-SecureString $env:METRO_SIGNING_PASSWORD -AsPlainText -Force |
            ConvertFrom-SecureString | Set-Content -LiteralPath $protectedPassword -Encoding ascii
    }
    if (!(Test-Path -LiteralPath $keystore)) {
        Invoke-BuildTool $keytool @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
            '-storepass:env', 'METRO_SIGNING_PASSWORD', '-alias', 'metrostep', '-keyalg', 'RSA',
            '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Metro Step Navigation')
    }
    Invoke-BuildTool $java @('-jar', $signer, 'sign', '--ks', $keystore, '--ks-key-alias', 'metrostep',
        '--ks-pass', 'env:METRO_SIGNING_PASSWORD', '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
} finally {
    [Environment]::SetEnvironmentVariable('METRO_SIGNING_PASSWORD', $null, 'Process')
}
Invoke-BuildTool $java @('-jar', $signer, 'verify', '--verbose', $signed)
Invoke-BuildTool $zipalign @('-c', '4', $signed)
$output = Join-Path $release "地铁单步导航-$version.apk"
Move-Item -LiteralPath $signed -Destination $output -Force
Get-FileHash -LiteralPath $output -Algorithm SHA256
