param(
    [string]$ServerPath = 'G:/Minecraftserver/DaemonData/Servers/1',
    [string]$JavaBin = '',
    [switch]$PackageOnly
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$ServerRoot = (Resolve-Path -LiteralPath $ServerPath).Path
if (!$JavaBin) { $JavaBin = [IO.Path]::GetFullPath((Join-Path $ServerRoot '../../Tools/Java/25/bin')) }
$JavaCompiler = Join-Path $JavaBin 'javac.exe'
$JarTool = Join-Path $JavaBin 'jar.exe'
foreach ($tool in @($JavaCompiler, $JarTool)) {
    if (!(Test-Path -LiteralPath $tool)) { throw "Missing JDK tool: $tool. Supply -JavaBin." }
}
$BuildRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'build-output'))
$ClassRoot = [IO.Path]::GetFullPath((Join-Path $BuildRoot 'classes'))
if ((Split-Path -Parent $BuildRoot) -ne $ProjectRoot -or (Split-Path -Parent $ClassRoot) -ne $BuildRoot) {
    throw 'Build paths do not resolve within this project.'
}
New-Item -ItemType Directory -Path $BuildRoot -Force | Out-Null
function Get-LatestLibrary([string]$GroupPath) {
    $directory = Join-Path (Join-Path $ServerRoot 'libraries') $GroupPath
    if (!(Test-Path -LiteralPath $directory)) { throw "Missing dependency directory: $directory" }
    $files = @(Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar')
    $selected = $files | Sort-Object -Property @{
        Expression = {
            $match = [regex]::Match($_.Directory.Name, '^[0-9]+([.][0-9]+){1,3}')
            if ($match.Success) { [version]$match.Value } else { [version]'0.0' }
        }
        Descending = $true
    } | Select-Object -First 1
    if (!$selected) { throw "No JAR found under $directory" }
    return $selected.FullName
}
function Quote-JavaArgument([string]$Value) {
    return '"' + $Value.Replace([char]92, [char]47) + '"'
}
if (!$PackageOnly) {
    if (Test-Path -LiteralPath $ClassRoot) {
        $resolved = (Resolve-Path -LiteralPath $ClassRoot).Path
        $reparsePoint = ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
        if ($resolved -ne $ClassRoot -or $reparsePoint) { throw 'Unexpected build directory.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ClassRoot -Force | Out-Null
    $apiRoot = Join-Path $ServerRoot 'libraries/io/papermc/paper/paper-api'
    $api = Get-ChildItem -LiteralPath $apiRoot -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Directory.Name -match '^26[.]2[.]build[.][0-9]+-stable$' } |
        Sort-Object -Property @{
            Expression = { [int][regex]::Match($_.Directory.Name, 'build[.]([0-9]+)').Groups[1].Value }
            Descending = $true
        } | Select-Object -First 1
    if (!$api) { throw 'Paper 26.2 stable API is required.' }
    $dependencies = @($api.FullName)
    foreach ($group in @(
        'net/kyori/adventure-api', 'net/kyori/adventure-key',
        'net/kyori/examination-api', 'net/kyori/examination-string',
        'net/md-5/bungeecord-chat', 'com/google/guava/guava',
        'org/jetbrains/annotations', 'org/joml/joml'
    )) { $dependencies += Get-LatestLibrary $group }
    $papi = Join-Path $ProjectRoot 'libs/PlaceholderAPI-2.12.3.jar'
    if (!(Test-Path -LiteralPath $papi)) {
        $found = Get-ChildItem -LiteralPath (Join-Path $ServerRoot 'plugins') -File -Filter 'PlaceholderAPI*.jar' |
            Select-Object -First 1
        if (!$found) { throw 'PlaceholderAPI JAR is required in libs/ or the server plugins directory.' }
        $papi = $found.FullName
    }
    $dependencies += $papi
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src/main/java') -Recurse -File -Filter '*.java')
    if (!$sources.Count) { throw 'No Java source files found.' }
    $arguments = @('-encoding', 'UTF-8', '--release', '25', '-classpath',
        (Quote-JavaArgument ($dependencies -join ';')), '-d', (Quote-JavaArgument $ClassRoot))
    $arguments += @($sources | ForEach-Object { Quote-JavaArgument $_.FullName })
    $argumentFile = Join-Path $BuildRoot 'javac.args'
    [IO.File]::WriteAllLines($argumentFile, [string[]]$arguments, [Text.UTF8Encoding]::new($false))
    & $JavaCompiler ('@' + $argumentFile)
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
}
if (!(Test-Path -LiteralPath (Join-Path $ClassRoot 'com/clawx/elitemobs/EliteMobsPlugin.class'))) {
    throw 'Compiled plugin classes are missing. Run the build without -PackageOnly.'
}
$resources = Join-Path $ProjectRoot 'src/main/resources'
$versionLine = Get-Content -LiteralPath (Join-Path $resources 'plugin.yml') -Encoding UTF8 |
    Where-Object { $_ -match '^version:' } | Select-Object -First 1
$pluginVersion = ($versionLine -replace '^version:[ ]*', '').Trim().Trim([char[]]@([char]39, [char]34))
if ($pluginVersion -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid plugin version.' }
$artifact = Join-Path $BuildRoot ('EliteMobs-' + $pluginVersion + '-flightless.jar')
& $JarTool --create --file $artifact -C $ClassRoot . -C $resources .
if ($LASTEXITCODE -ne 0) { throw 'Plugin packaging failed.' }
Get-Item -LiteralPath $artifact | Select-Object FullName, Length
