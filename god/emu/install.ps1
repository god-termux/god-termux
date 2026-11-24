param([switch]$Build)
. "$PSScriptRoot\env.ps1"
$root = Resolve-Path "$PSScriptRoot\..\.."
if ($Build) { Push-Location $root; .\gradlew.bat :app:assembleDebug; Pop-Location }
$apk = Get-ChildItem "$root\app\build\outputs\apk\debug\*_x86_64.apk" | Select -First 1
& $Adb install -r $apk.FullName
& $Adb shell am start -n com.termux/.app.TermuxActivity
