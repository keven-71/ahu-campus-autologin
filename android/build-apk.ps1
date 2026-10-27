param([string]$JavaHome = $env:JAVA_HOME, [string]$SdkRoot = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $JavaHome) {
    $javaCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source) }
}
if (-not (Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw 'Pass -JavaHome pointing to a JDK (17 or newer), or set JAVA_HOME.' }
if ($SdkRoot) {
    $platformJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
    $tools = Join-Path $SdkRoot 'build-tools\35.0.0'
} else {
    $platformJar = Join-Path $projectRoot '.tooling\platform\android-35\android.jar'
    $tools = Join-Path $projectRoot '.tooling\build-tools\android-15'
}
if (-not (Test-Path -LiteralPath $platformJar) -or -not (Test-Path -LiteralPath "$tools\aapt2.exe")) {
    throw 'Install Android platform 35 and build-tools 35.0.0, or follow README.md to prepare .tooling.'
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed with exit code $LASTEXITCODE." }
}
$stage = Join-Path $projectRoot ('build\' + [Guid]::NewGuid().ToString('N'))
$generated = Join-Path $stage 'generated'
$classes = Join-Path $stage 'classes'
$dex = Join-Path $stage 'dex'
$outputDir = Join-Path $projectRoot 'output'
New-Item -ItemType Directory -Force -Path $stage,$generated,$classes,$dex,$outputDir | Out-Null
$resources = Join-Path $stage 'compiled.zip'
$unsigned = Join-Path $stage 'unsigned.apk'
$aligned = Join-Path $stage 'aligned.apk'
$classJar = Join-Path $stage 'classes.jar'
$apk = Join-Path $outputDir 'ahu-campus-android.apk'
$manifestFile = Join-Path $stage 'AndroidManifest.xml'
$manifest = [xml](Get-Content -LiteralPath "$projectRoot\app\src\main\AndroidManifest.xml" -Raw)
$manifest.DocumentElement.SetAttribute('package', 'cn.edu.ahu.autologin')
$manifest.Save($manifestFile)
Invoke-Checked "$tools\aapt2.exe" @('compile','--dir',"$projectRoot\app\src\main\res",'-o',$resources)
Invoke-Checked "$tools\aapt2.exe" @('link','-o',$unsigned,'--manifest',$manifestFile,'-I',$platformJar,'--java',$generated,'--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0',$resources)
$sources = @(Get-ChildItem "$projectRoot\app\src\main\java",$generated -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName })
$sourceList = Join-Path $stage 'sources.txt'
$sourceLines = @($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' })
[IO.File]::WriteAllLines($sourceList, $sourceLines, [Text.UTF8Encoding]::new($false))
Invoke-Checked "$JavaHome\bin\javac.exe" @('--release','8','-encoding','UTF-8','-classpath',$platformJar,'-d',$classes,"@$sourceList")
Invoke-Checked "$JavaHome\bin\jar.exe" @('cf',$classJar,'-C',$classes,'.')
Invoke-Checked "$JavaHome\bin\java.exe" @('-cp',"$tools\lib\d8.jar",'com.android.tools.r8.D8','--min-api','26','--lib',$platformJar,'--output',$dex,$classJar)
Invoke-Checked "$JavaHome\bin\jar.exe" @('uf',$unsigned,'-C',$dex,'classes.dex')
Invoke-Checked "$tools\zipalign.exe" @('-f','4',$unsigned,$aligned)
$keyDir = Join-Path $projectRoot '.signing'
$key = Join-Path $keyDir 'personal.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
    Invoke-Checked "$JavaHome\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-alias','ahu-personal','-storepass','android','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=AHU Campus Personal Build')
}
Invoke-Checked "$JavaHome\bin\java.exe" @('-jar',"$tools\lib\apksigner.jar",'sign','--ks',$key,'--ks-key-alias','ahu-personal','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',$apk,$aligned)
Invoke-Checked "$JavaHome\bin\java.exe" @('-jar',"$tools\lib\apksigner.jar",'verify','--verbose',$apk)
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Output "APK: $apk"
