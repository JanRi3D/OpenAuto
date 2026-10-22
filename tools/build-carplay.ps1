param(
    [string]$RuntimeAssetsDir,
    [switch]$UseDiPlayPreviewIdentity,
    [ValidateSet('Debug','Release')][string]$Variant='Release'
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$taskBuild=Join-Path $project 'build\carplay-reference'
New-Item -ItemType Directory -Path $taskBuild -Force | Out-Null
if($UseDiPlayPreviewIdentity) {
    $apk=Join-Path $taskBuild 'DiPlay-0.2.21.apk'
    $expected='916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3'
    if(!(Test-Path -LiteralPath $apk)) {
        Invoke-WebRequest -Uri 'https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.21/DiPlay-0.2.21.apk' -OutFile $apk
    }
    if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw 'The reference APK hash does not match the published release.'
    }
    $RuntimeAssetsDir=Join-Path $project 'build\carplay-runtime'
    $destination=Join-Path $RuntimeAssetsDir 'carplay-mfi'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach($name in @('identity.pk8','certificate.p7b')) {
            $entry=$zip.GetEntry('assets/offline-mfi/'+$name)
            if($null-eq$entry -or $entry.Length-le0 -or $entry.Length-gt16384) {throw 'Reference runtime asset is missing or too large.'}
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $destination $name),$true)
        }
    }finally{$zip.Dispose()}
}
if([string]::IsNullOrWhiteSpace($RuntimeAssetsDir)){throw 'Supply -RuntimeAssetsDir or explicitly select -UseDiPlayPreviewIdentity.'}
$RuntimeAssetsDir=(Resolve-Path -LiteralPath $RuntimeAssetsDir).Path
foreach($name in @('identity.pk8','certificate.p7b')) {
    $f=Get-Item -LiteralPath (Join-Path $RuntimeAssetsDir ('carplay-mfi\'+$name))
    if($f.Length-le0 -or $f.Length-gt16384){throw 'Invalid accessory asset size.'}
}
$previousAssets=$env:OPENAUTO_CARPLAY_ASSETS
$previousJava=$env:JAVA_HOME
try {
    $env:OPENAUTO_CARPLAY_ASSETS=$RuntimeAssetsDir
    if(!$env:JAVA_HOME -and (Test-Path -LiteralPath 'C:\Program Files\Android\Android Studio\jbr')) {
        $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    }
    Push-Location -LiteralPath $project
    try {
        & .\gradlew.bat ':app:testDebugUnitTest' (':app:lint'+$Variant) (':app:assemble'+$Variant)
        if($LASTEXITCODE-ne0){throw 'CarPlay verification/build failed.'}
    }finally{Pop-Location}
    $kind=$Variant.ToLowerInvariant()
    $output=Join-Path $project ('app\build\outputs\apk\'+$kind+'\app-'+$kind+'.apk')
    if(!(Test-Path -LiteralPath $output)){throw 'Expected signed APK was not produced. Check the local release signing configuration.'}
    $deliverable=Join-Path $project ('dist\openauto-0.6.0-carplay1-'+$kind+'.apk')
    Copy-Item -LiteralPath $output -Destination $deliverable -Force
    $apkHash=(Get-FileHash -LiteralPath $deliverable -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($deliverable+'.sha256') -Encoding ascii -Value ($apkHash+'  '+(Split-Path -Leaf $deliverable))
    [pscustomobject]@{Path=$deliverable;SHA256=$apkHash}
}finally {
    $env:OPENAUTO_CARPLAY_ASSETS=$previousAssets
    $env:JAVA_HOME=$previousJava
}
