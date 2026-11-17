# One-time: install emulator + system image on E:, create AVDs (phone, tablet)
. "$PSScriptRoot\env.ps1"
& $SdkMgr --sdk_root=$Sdk "emulator" $Image "platform-tools"
foreach ($d in @(@{n='gt_phone';dev='pixel_6'}, @{n='gt_tablet';dev='pixel_tablet'})) {
  if (-not (Test-Path "$env:ANDROID_AVD_HOME\$($d.n).avd")) {
    'no' | & $AvdMgr create avd -n $d.n -k $Image -d $d.dev --force
  }
}
# AVD tuning: RAM, cores, host GPU
foreach ($n in 'gt_phone','gt_tablet') {
  $ini = "$env:ANDROID_AVD_HOME\$n.avd\config.ini"
  if (Test-Path $ini) {
    $c = Get-Content $ini | Where-Object { $_ -notmatch '^(hw.ramSize|hw.cpu.ncore|hw.gpu.enabled|hw.gpu.mode|hw.keyboard)=' }
    $c += 'hw.ramSize=3072','hw.cpu.ncore=4','hw.gpu.enabled=yes','hw.gpu.mode=host','hw.keyboard=yes'
    Set-Content $ini $c
  }
}
& $Emu -accel-check
