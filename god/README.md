# god-termux

Unofficial fork of [Termux](https://github.com/termux/termux-app) (GPLv3). Not affiliated with the Termux project.

## What is added
- **Mod system**: built-in mods (compiled in) and third-party mods in `~/.termux/mods/` (`mod.json` + `classes.dex` + `lib/`), crash-loop guard (`~/.termux/mods/DISABLED`).
- **Built-in mods**: *Mods* manager, *Privileged shell* (built-in ADB over Wireless debugging, `gsh` command, no Shizuku needed), *glibc & Debian* (runtime + UI), GL demo.
- **glibc/FHS shim** (`god/fhs`): run stock glibc binaries without a runner, `/lib /usr /bin /etc /tmp /var` path emulation (LD_PRELOAD for dynamic programs, seccomp supervisor `fhs-run` for static ones).
- **`dapt`** (`god/runtime/dapt`): isolated Debian (arm64) tree in `$PREFIX/debian` with its own apt/dpkg (`dapt init`, `dapt update`, `dapt install …`).
- **New UI**: palette, rounded cards, restyled drawer/settings/dialogs (console untouched).

## Layout
- `app/src/main/java/com/termux/mod` loader (`ModLoader`, `Builtin`, `GodRuntime`, `ModActivity`)
- `app/src/main/java/{modsmgr,adbfind,glibcrt,gldemo,godui}` built-in mods and the UI kit
- `god/fhs` shim sources, `god/runtime` scripts, `god/build-local.sh` on-device build

## Build
`god/build-local.sh` builds the APK on an aarch64 Termux device (Java 17, no NDK needed; native libs are built with Termux clang).
The debug build is signed with Termux's public test key (`app/testkey_untrusted.jks`): do not distribute builds signed with it
to other people; create your own keystore for releases.

## License
GPLv3, same as Termux (see `LICENSE.md`). Termux is a trademark of its authors.
