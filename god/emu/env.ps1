# god-termux emulator env: everything lives on E:
$env:ANDROID_HOME     = 'E:\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = 'E:\android-avd'
$env:ANDROID_USER_HOME = 'E:\android-avd\.android'
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME, $env:ANDROID_USER_HOME | Out-Null
$script:Sdk   = $env:ANDROID_HOME
$script:Adb   = "$Sdk\platform-tools\adb.exe"
$script:Emu   = "$Sdk\emulator\emulator.exe"
$script:SdkMgr = "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$script:AvdMgr = "$Sdk\cmdline-tools\latest\bin\avdmanager.bat"
$script:Image = 'system-images;android-30;default;x86_64'
