param([string]$JavaHome = $env:JAVA_HOME, [string]$JsonJar = "$PSScriptRoot\.tooling\json-20240303.jar")
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $javaCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source) }
}
if (-not (Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw 'Pass -JavaHome pointing to a JDK.' }
if (-not (Test-Path -LiteralPath $JsonJar)) { throw 'Download org.json:json:20240303 for the JVM-only tests; see README.md.' }
$classes = Join-Path $PSScriptRoot ('build\test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& "$JavaHome\bin\javac.exe" --release 8 -encoding UTF-8 -cp $JsonJar -d $classes "$PSScriptRoot\app\src\main\java\cn\edu\ahu\autologin\PortalProtocol.java" "$PSScriptRoot\tests\ProtocolTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& "$JavaHome\bin\java.exe" -ea -cp "$classes;$JsonJar" cn.edu.ahu.autologin.ProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'Protocol checks failed.' }
