#!/bin/bash
#
# build-wifi-binaries.sh
#
# Compila los binarios nativos del módulo Pixie Dust de AmWF:
#   - pixiewps      (arm64-v8a y armeabi-v7a)
#   - wpa_supplicant + wpa_cli (arm64-v8a, con libnl 3.2.25 propia)
#
# Uso:
#   ./build-wifi-binaries.sh <arm64-v8a|armeabi-v7a>
#
# Requisitos:
#   - Android NDK r27 o posterior (variable ANDROID_NDK o ANDROID_NDK_HOME)
#   - Fuente de pixiewps en ../pixiewps-master/ (PIXIEWPS_DIR)
#   - Fuente de wpa_supplicant 2.11 en ../wpa_supplicant-2.11/ (WPA_DIR)
#   - Fuente de libnl 3.2.25 en ../libnl-3.2.25/ (LIBNL_DIR)
#
# El wpa_supplicant se compila SIN CONFIG_ANDROID_LOG y CON CONFIG_DEBUG_FILE
# para que los hexdump WPS completos salgan por stdout. El supplicant de
# Android los trunca a 32 bytes y los manda a logcat, lo que hace imposible
# recuperar las claves DH de 192 bytes que necesita pixiewps.
#
# Licencia: GPL-3.0-or-later (igual que AmWF)

set -euo pipefail

ARCH="${1:-}"
if [[ -z "$ARCH" ]]; then
    echo "Uso: $0 <arm64-v8a|armeabi-v7a>"
    exit 1
fi

ANDROID_NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.0.12077973}}"
ANDROID_API="${ANDROID_API:-24}"
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
export PATH="$TOOLCHAIN/bin:$PATH"

case "$ARCH" in
    arm64-v8a)
        TRIPLE="aarch64-linux-android"
        SUFFIX=""
        ;;
    armeabi-v7a)
        TRIPLE="armv7a-linux-androideabi"
        SUFFIX="_32"
        ;;
    *)
        echo "Arquitectura no soportada: $ARCH (usa arm64-v8a o armeabi-v7a)"
        exit 1
        ;;
esac

CC="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/wifi"
mkdir -p "$ASSETS_DIR"

# ---------------------------------------------------------------------------
# 1. pixiewps (GPL-3.0-or-later, wiire-a/pixiewps)
# ---------------------------------------------------------------------------
PIXIEWPS_DIR="${PIXIEWPS_DIR:-$SCRIPT_DIR/../pixiewps-master}"
if [[ -d "$PIXIEWPS_DIR" ]]; then
    echo "[pixiewps] Compilando para $ARCH ..."
    make -C "$PIXIEWPS_DIR" clean >/dev/null 2>&1 || true
    make -C "$PIXIEWPS_DIR" \
        CC="$CC" \
        CFLAGS="-O3 -DANDROID -fPIE -fPIC -I$SYSROOT/usr/include -I$SYSROOT/usr/include/$TRIPLE" \
        LDFLAGS="-pie -L$SYSROOT/usr/lib/$TRIPLE/$ANDROID_API" \
        LIBS="" \
        OPENSSL=0 \
        -j"$(nproc)"
    if [[ -f "$PIXIEWPS_DIR/pixiewps" ]]; then
        "$STRIP" "$PIXIEWPS_DIR/pixiewps"
        cp "$PIXIEWPS_DIR/pixiewps" "$ASSETS_DIR/pixiewps$SUFFIX"
        echo "[pixiewps] OK -> assets/wifi/pixiewps$SUFFIX"
    else
        echo "[pixiewps] ERROR: binario no generado"
        exit 1
    fi
else
    echo "[pixiewps] SALTADO: no se encontró $PIXIEWPS_DIR"
fi

# ---------------------------------------------------------------------------
# 2. libnl 3.2.25 (LGPL-2.1) — solo arm64-v8a
# ---------------------------------------------------------------------------
# wpa_supplicant 2.11 usa la API genl de libnl 3. La versión 3.2.25 no incluye
# NLA_S8/nla_get_s8, que wpa_supplicant referencia; el parche los añade.
if [[ "$ARCH" != "arm64-v8a" ]]; then
    echo "[wpa_supplicant] SALTADO: el supplicant empaquetado solo se compila para arm64-v8a"
    echo "[wpa_cli] SALTADO: este script no regenera wpa_cli_32; se conserva el asset precompilado"
    echo "=== Hecho para $ARCH (solo pixiewps) ==="
    ls -la "$ASSETS_DIR/"
    exit 0
fi

LIBNL_DIR="${LIBNL_DIR:-$SCRIPT_DIR/../libnl-3.2.25}"
if [[ ! -d "$LIBNL_DIR" ]]; then
    echo "[libnl] ERROR: no se encontró $LIBNL_DIR"
    exit 1
fi

echo "[libnl] Compilando libnl 3.2.25 para $ARCH ..."
cd "$LIBNL_DIR"

if ! grep -q "NLA_S8" include/netlink/attr.h; then
    patch -p1 < "$SCRIPT_DIR/patches/libnl-3.2.25-wpa2.11.patch"
fi

# Bionic integra pthread/rt en libc; los linker scripts evitan que libtool
# busque -lpthread/-lrt inexistentes en el sysroot del NDK.
mkdir -p build-stubs
printf 'INPUT(-lc)\n' > build-stubs/libpthread.so
printf 'INPUT(-lc)\n' > build-stubs/librt.so

if [[ ! -f Makefile ]]; then
    ./configure \
        --host=aarch64-linux-android \
        --prefix="$LIBNL_DIR/out" \
        --disable-static --enable-shared \
        CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
        CFLAGS="-O2 -fPIC" \
        LDFLAGS="-L$LIBNL_DIR/build-stubs -pie -Wl,--undefined-version" \
        LIBS="-lpthread"
fi

make -C lib -j"$(nproc)"

cp lib/.libs/libnl-3.so.200.20.0 "$ASSETS_DIR/libnl-3.so.200"
cp lib/.libs/libnl-genl-3.so.200.20.0 "$ASSETS_DIR/libnl-genl-3.so.200"
"$STRIP" --strip-unneeded "$ASSETS_DIR/libnl-3.so.200" "$ASSETS_DIR/libnl-genl-3.so.200"
chmod 644 "$ASSETS_DIR/libnl-3.so.200" "$ASSETS_DIR/libnl-genl-3.so.200"
echo "[libnl] OK -> assets/wifi/libnl-3.so.200 + libnl-genl-3.so.200"

# ---------------------------------------------------------------------------
# 3. wpa_supplicant y wpa_cli (BSD-3-Clause, w1.fi)
# ---------------------------------------------------------------------------
WPA_DIR="${WPA_DIR:-$SCRIPT_DIR/../wpa_supplicant-2.11}"
if [[ ! -d "$WPA_DIR/wpa_supplicant" ]]; then
    echo "[wpa_supplicant] ERROR: no se encontró $WPA_DIR/wpa_supplicant"
    exit 1
fi

echo "[wpa_supplicant] Compilando wpa_supplicant 2.11 para $ARCH ..."
cd "$WPA_DIR/wpa_supplicant"

# Sin este flush, stdout es block-buffered al ser una pipe y los hexdump
# completos pueden quedarse en el buffer hasta que el proceso termina.
if ! grep -q "fflush(stdout)" ../src/utils/wpa_debug.c; then
    patch -p1 -d "$WPA_DIR" < "$SCRIPT_DIR/patches/wpa_supplicant-2.11-stdout-flush.patch"
fi

cp defconfig .config

# Desactivar características que no necesita una estación WPS y que añaden
# dependencias problemáticas en bionic (DBus, syslog, AP, P2P, DPP, SAE,
# EAP-PWD, drivers wired/macsec/wext...).
for feature in \
    CONFIG_CTRL_IFACE_DBUS_NEW \
    CONFIG_CTRL_IFACE_DBUS_INTRO \
    CONFIG_DEBUG_SYSLOG \
    CONFIG_DRIVER_WIRED \
    CONFIG_DRIVER_MACSEC_LINUX \
    CONFIG_DRIVER_WEXT \
    CONFIG_AP \
    CONFIG_MACSEC \
    CONFIG_SMARTCARD \
    CONFIG_SAE \
    CONFIG_IEEE80211R \
    CONFIG_INTERWORKING \
    CONFIG_HS20 \
    CONFIG_P2P \
    CONFIG_TDLS \
    CONFIG_WIFI_DISPLAY \
    CONFIG_IBSS_RSN \
    CONFIG_DPP \
    CONFIG_DPP2 \
    CONFIG_SME \
    CONFIG_OFFCHANNEL \
    CONFIG_GAS \
    CONFIG_GAS_SERVER \
    CONFIG_JSON \
    CONFIG_IEEE80211AC \
    CONFIG_IEEE80211AX \
    CONFIG_BGSCAN \
    CONFIG_BGSCAN_SIMPLE \
    CONFIG_EAP_PWD
do
    sed -i "s/^$feature=y/#$feature=y/" .config
done

# TLS interno + libtommath: evita depender de OpenSSL en el dispositivo.
sed -i \
    -e 's/^#CONFIG_TLS=openssl/CONFIG_TLS=internal/' \
    -e 's/^#CONFIG_INTERNAL_LIBTOMMATH=y/CONFIG_INTERNAL_LIBTOMMATH=y/' \
    -e 's/^#CONFIG_INTERNAL_LIBTOMMATH_FAST=y/CONFIG_INTERNAL_LIBTOMMATH_FAST=y/' \
    -e 's/^#CONFIG_NO_RANDOM_POOL=y/CONFIG_NO_RANDOM_POOL=y/' \
    .config

# libnl vive en un directorio de includes filtrado: si se añade
# $LIBNL_DIR/include directamente, su copia antigua de <linux/if.h> tapa la
# de bionic y provoca "struct ifreq has incomplete type".
mkdir -p "$LIBNL_DIR/build-include"
ln -sfn "$LIBNL_DIR/include/netlink" "$LIBNL_DIR/build-include/netlink"

cat >> .config <<EOF

# Añadido por scripts/build-wifi-binaries.sh
CFLAGS += -I$LIBNL_DIR/build-include -I..
LDFLAGS += -L$LIBNL_DIR/lib/.libs
LDFLAGS += -L$LIBNL_DIR/build-stubs
EOF

# wpa_ctrl.c y os_unix.c incluyen <private/android_filesystem_config.h>.
mkdir -p "$WPA_DIR/private"
cat > "$WPA_DIR/private/android_filesystem_config.h" <<'EOF'
#ifndef ANDROID_FILESYSTEM_CONFIG_H
#define ANDROID_FILESYSTEM_CONFIG_H
#define AID_ROOT 0
#define AID_SYSTEM 1000
#define AID_WIFI 1010
#define AID_KEYSTORE 1017
#define AID_INET 3003
#endif
EOF

make clean >/dev/null 2>&1 || true
make -j"$(nproc)" CC="$CC" AR="$AR" STRIP="$STRIP"

if [[ -f wpa_supplicant ]]; then
    "$STRIP" --strip-unneeded wpa_supplicant
    cp wpa_supplicant "$ASSETS_DIR/wpa_supplicant"
    chmod 755 "$ASSETS_DIR/wpa_supplicant"
    echo "[wpa_supplicant] OK -> assets/wifi/wpa_supplicant"
else
    echo "[wpa_supplicant] ERROR: binario no generado"
    exit 1
fi

if [[ -f wpa_cli ]]; then
    "$STRIP" --strip-unneeded wpa_cli
    cp wpa_cli "$ASSETS_DIR/wpa_cli"
    chmod 755 "$ASSETS_DIR/wpa_cli"
    echo "[wpa_cli] OK -> assets/wifi/wpa_cli"
else
    echo "[wpa_cli] ERROR: binario no generado"
    exit 1
fi

echo "=== Hecho para $ARCH ==="
ls -la "$ASSETS_DIR/"
