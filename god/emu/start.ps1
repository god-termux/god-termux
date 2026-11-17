param([ValidateSet('phone','tablet')]$Avd='phone', [switch]$Headless)
. "$PSScriptRoot\env.ps1"
$args = @('-avd',"gt_$Avd",'-no-snapshot-save','-no-boot-anim','-netdelay','none','-netspeed','full')
if ($Headless) { $args += '-no-window','-no-audio' }
Start-Process $Emu -ArgumentList $args -WindowStyle Hidden
& $Adb wait-for-device
do { Start-Sleep 3 } until ((& $Adb shell getprop sys.boot_completed 2>$null).Trim() -eq '1')
"booted: gt_$Avd"
