# Collects everything needed to diagnose OpenAuto on a real head unit into radio-logs\<timestamp>\.
#
#   powershell -ExecutionPolicy Bypass -File tools\collect-radio-logs.ps1 <radio-ip>[:port] [-Seconds 90]
#   powershell -ExecutionPolicy Bypass -File tools\collect-radio-logs.ps1 <adb-serial> -Serial [-Seconds 90]
#
# The first form uses ADB over Wi-Fi (port defaults to 5555); the second a device adb already lists.
# The script records for -Seconds (default 90): start it, then start Android Auto on the radio and keep
# using it until the script says it is done. Only commands that exist on Android 4.1 are used.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Target,
    [int]$Seconds = 90,
    [switch]$Serial
)

$adb = $env:ADB
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source } else { Write-Host "adb not found. Set the ADB environment variable to adb.exe."; exit 1 }
}

if ($Serial) {
    $device = $Target
} else {
    if ($Target -notmatch ':') { $Target = "${Target}:5555" }
    & $adb connect $Target
    $device = $Target
}

$state = (& $adb -s $device get-state 2>$null | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "Cannot talk to '$device' (state: '$state')."
    Write-Host "The radio must have ADB over network enabled and be on the same network as this PC."
    exit 1
}

$out = Join-Path 'radio-logs' (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force $out | Out-Null
Write-Host "Writing to $out"

function Save([string]$name, [string[]]$adbArgs) {
    & $adb -s $device @adbArgs | Out-File -Encoding utf8 (Join-Path $out $name)
}

# ---- static facts -------------------------------------------------------------------------------
Save 'getprop.txt'               @('shell', 'getprop')
Save 'cpuinfo.txt'               @('shell', 'cat', '/proc/cpuinfo')
Save 'meminfo.txt'               @('shell', 'cat', '/proc/meminfo')
Save 'media_codecs.xml'          @('shell', 'cat', '/system/etc/media_codecs.xml')
Save 'dumpsys-window-before.txt' @('shell', 'dumpsys', 'window')
Save 'dumpsys-package.txt'       @('shell', 'dumpsys', 'package', 'me.ri3d.openauto')
Save 'dumpsys-usb.txt'           @('shell', 'dumpsys', 'usb')
Save 'cpufreq.txt'               @('shell', 'cat', '/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq', '/sys/devices/system/cpu/online')
# Every USB device the kernel sees (the "phantom" device shows up here). No double quotes inside:
# Windows PowerShell 5.1 mangles them when passing arguments to native programs.
Save 'usb-sysfs.txt' @('shell', 'for d in /sys/bus/usb/devices/*; do echo == $d; cat $d/idVendor $d/idProduct $d/bDeviceClass $d/manufacturer $d/product $d/bInterfaceClass $d/bInterfaceSubClass $d/bInterfaceProtocol 2>/dev/null; done')

# ---- live recording ---------------------------------------------------------------------------------
& $adb -s $device logcat -c
Write-Host ""
Write-Host ">>> Recording for $Seconds s. Start Android Auto on the radio NOW and keep using it."

function Sample([string]$label) {
    Save "top-$label.txt"             @('shell', 'top', '-n', '1', '-m', '30', '-t')
    Save "surfaceflinger-$label.txt"  @('shell', 'dumpsys', 'SurfaceFlinger')
    Save "meminfo-app-$label.txt"     @('shell', 'dumpsys', 'meminfo', 'me.ri3d.openauto')
    Save "dumpsys-windows-$label.txt" @('shell', 'dumpsys', 'window', 'windows')
    Save "media-player-$label.txt"    @('shell', 'dumpsys', 'media.player')
    # Screenshot via a file on the device: piping binary data through PowerShell would corrupt it.
    & $adb -s $device shell screencap -p /data/local/tmp/oa-shot.png | Out-Null
    & $adb -s $device pull /data/local/tmp/oa-shot.png (Join-Path $out "screen-$label.png") | Out-Null
    Write-Host "    sample $label taken"
}

$third = [Math]::Max(1, [int]($Seconds / 3))
Start-Sleep -Seconds $third; Sample '1'
Start-Sleep -Seconds $third; Sample '2'
Start-Sleep -Seconds $third; Sample '3'
Save 'logcat.txt' @('logcat', '-d', '-v', 'threadtime')

Write-Host ""
Write-Host "Done. Files are in ${out}:"
Get-ChildItem $out | Format-Table Name, Length -AutoSize
