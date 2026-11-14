# glibc-fhs: transparent glibc exec + FHS path emulation. Disable: export FHS_OFF=1 (or delete this file).
# The shim is only enabled if its files match the SHA-256 sums recorded at install time.
if [ -z "$FHS_OFF" ] && [ ! -f "$HOME/.termux/fhs.off" ] && [ -f "$PREFIX/glibc/shim/libfhs-bionic.so" ]; then
  if (cd "$PREFIX/glibc/shim" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1); then
    case ":$LD_PRELOAD:" in
      *libfhs-bionic.so*) ;;
      *) export LD_PRELOAD="$PREFIX/glibc/shim/libfhs-bionic.so${LD_PRELOAD:+:$LD_PRELOAD}" ;;
    esac
    # the shell itself must carry the shim too: re-exec once (interactive login shells only)
    if [ -n "$BASH" ] && [ -z "$FHS_REEXEC" ] && [ -t 0 ] && case $- in *i*) true;; *) false;; esac \
       && case "$(< "/proc/$$/maps")" in *libfhs-bionic*) false;; *) true;; esac 2>/dev/null; then
      export FHS_REEXEC=1
      exec -a "-bash" "$BASH"
    fi
  else
    echo "glibc-fhs: shim checksum mismatch - NOT loading it (run ~/mods-src/fhs/install.sh)" >&2
  fi
fi

# Debian (arm64) tree installed with ddpkg
case ":$PATH:" in *:"$PREFIX/debian/usr/bin":*) ;; *) export PATH="$PATH:$PREFIX/debian/usr/bin:$PREFIX/debian/usr/games:$PREFIX/debian/bin" ;; esac
# sbin last: Termux commands win, but Debian daemons and tools (zmap, ...) are found; checked on its own so shells that already have usr/bin get it too
case ":$PATH:" in *:"$PREFIX/debian/usr/sbin":*) ;; *) export PATH="$PATH:$PREFIX/debian/usr/sbin:$PREFIX/debian/sbin" ;; esac
