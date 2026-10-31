[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$SdkHome = $env:ANDROID_HOME,
    [string[]]$Tasks = @('assembleDebug', 'test', 'lint'),
    [string]$MavenMirror
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$jdk = (Resolve-Path -LiteralPath $JdkHome).Path
$sdk = (Resolve-Path -LiteralPath $SdkHome).Path
if (!(Test-Path -LiteralPath (Join-Path $jdk 'bin/javac.exe'))) { throw 'JdkHome must point to a full JDK 17.' }
if (!(Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-37.2/android.jar'))) { throw 'Install Android SDK platform 37.2 first.' }
if ($MavenMirror -and !([uri]$MavenMirror).IsAbsoluteUri) { throw 'MavenMirror must be an absolute HTTPS URL.' }
if ($MavenMirror -and ([uri]$MavenMirror).Scheme -ne 'https') { throw 'MavenMirror must use HTTPS.' }

# JVM test discovery and Android tools need an ASCII path on Windows.
# Allocate only unused drive letters, and remove only aliases created by this invocation.
$aliases = [System.Collections.Generic.List[string]]::new()
function New-BuildAlias([string]$target) {
    foreach ($letter in [char[]]'ZYXWVUTSRQPONMLKJIHGFED') {
        $drive = "$($letter):"
        if (!(Test-Path "$drive\") -and !(Get-PSDrive -Name $letter -ErrorAction SilentlyContinue)) {
            & subst.exe $drive $target
            if ($LASTEXITCODE -ne 0) { throw "Cannot create build alias $drive" }
            $aliases.Add($drive)
            return $drive
        }
    }
    throw 'No free drive letter is available for the Android build.'
}

$localProperties = Join-Path $repository 'android/local.properties'
$hadLocalProperties = Test-Path -LiteralPath $localProperties
$originalProperties = $null
if ($hadLocalProperties) { $originalProperties = [IO.File]::ReadAllBytes($localProperties) }
$originalJava = $env:JAVA_HOME
$originalAndroid = $env:ANDROID_HOME
$buildExit = 1
try {
    $projectAlias = New-BuildAlias $repository
    $jdkAlias = New-BuildAlias $jdk
    $sdkAlias = New-BuildAlias $sdk
    $env:JAVA_HOME = "$jdkAlias\"
    $env:ANDROID_HOME = "$sdkAlias\"
    $escapedSdkAlias = $sdkAlias.Replace(':', '\:')
    [IO.File]::WriteAllText($localProperties, "sdk.dir=$escapedSdkAlias/`n", [Text.UTF8Encoding]::new($false))
    $gradleArguments = @('-p', "$projectAlias\android") + $Tasks + @('--console=plain')
    if ($MavenMirror) { $gradleArguments += "-Pjunshi.mavenMirror=$MavenMirror" }
    & "$projectAlias\android\gradlew.bat" @gradleArguments
    $buildExit = $LASTEXITCODE
}
finally {
    try {
        if ($hadLocalProperties) { [IO.File]::WriteAllBytes($localProperties, $originalProperties) }
        elseif (Test-Path -LiteralPath $localProperties) { Remove-Item -LiteralPath $localProperties }
    }
    finally {
        $env:JAVA_HOME = $originalJava
        $env:ANDROID_HOME = $originalAndroid
        foreach ($drive in $aliases) { & subst.exe $drive /D }
    }
}
exit $buildExit
