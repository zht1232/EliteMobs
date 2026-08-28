# Build script for EliteMobs (Paper 26.2, JDK 25) - version auto-read from plugin.yml
# Usage: pwsh -File build-v29.17.0.ps1

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$JAVAC = "C:\Minecraftserver\DaemonData\Tools\Java\25\bin\javac.exe"
$JAR = "C:\Minecraftserver\DaemonData\Tools\Java\25\bin\jar.exe"
$Server = "C:\Minecraftserver\DaemonData\Servers\1"
$Libs = "$Server\libraries"

# auto version from plugin.yml (e.g. version: '29.17.1')
$VerLine = Get-Content "$Root\src\main\resources\plugin.yml" | Where-Object { $_ -match '^version:' } | Select-Object -First 1
$Ver = ($VerLine -replace ".*'([^']*)'.*", '$1').Trim()
if (-not $Ver) { Write-Host "cannot read version from plugin.yml"; exit 1 }

$Cp = @(
    "$Server\versions\26.2\paper-26.2.jar",
    "$Libs\io\papermc\paper\paper-api\26.2.build.112-stable\paper-api-26.2.build.112-stable.jar",
    "$Libs\net\kyori\adventure-api\5.2.0\adventure-api-5.2.0.jar",
    "$Libs\net\kyori\adventure-key\5.2.0\adventure-key-5.2.0.jar",
    "$Libs\com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar",
    "$Libs\net\md-5\bungeecord-chat\1.21-R0.2-deprecated+build.21\bungeecord-chat-1.21-R0.2-deprecated+build.21.jar",
    "$Libs\org\joml\joml\1.10.9\joml-1.10.9.jar",
    "$Server\plugins\PlaceholderAPI-2.12.3.jar"
) -join ";"

$Src = "$Root\src\main\java"
$Out = "$Root\classes"
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null

# Direct invocation (classpath is short; PowerShell quotes args with spaces itself)
$Files = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC "-encoding" "UTF-8" "--release" "25" "-cp" $Cp "-d" $Out @Files
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED"; exit 1 }
Write-Host "COMPILE OK"

# package jar
$Tmp = "$Root\jar-temp"
if (Test-Path $Tmp) { Remove-Item $Tmp -Recurse -Force }
New-Item -ItemType Directory -Path $Tmp | Out-Null
Copy-Item "$Out\*" $Tmp -Recurse -Force
Copy-Item "$Root\src\main\resources\plugin.yml" $Tmp
Copy-Item "$Root\src\main\resources\config.yml" $Tmp
Copy-Item "$Root\src\main\resources\messages.yml" $Tmp
Copy-Item "$Root\src\main\resources\mobs.yml" $Tmp
New-Item -ItemType Directory -Path "$Tmp\gems" -Force | Out-Null
Copy-Item "$Root\src\main\resources\gems\*" "$Tmp\gems\" -Force

$JarOut = "$Root\EliteMobs-$Ver.jar"
if (Test-Path $JarOut) { Remove-Item $JarOut -Force }
Push-Location $Tmp
& $JAR "cf" $JarOut "."
Pop-Location
Remove-Item $Tmp -Recurse -Force

if (Test-Path $JarOut) {
    Write-Host "JAR created: $JarOut"
} else {
    Write-Host "JAR creation failed"
    exit 1
}
