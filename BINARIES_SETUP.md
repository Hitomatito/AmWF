# BINARIES_SETUP — Binarios nativos para el módulo Pixie Dust

El módulo Pixie Dust de AmWF requiere varios binarios compilados para Android ARM.
**No se incluyen en el repositorio por motivos de licencia y tamaño.** Debes compilarlos
tú mismo y empaquetarlos en `app/src/main/assets/wifi/`.

## Estructura esperada en assets

```
app/src/main/assets/wifi/
├── wpa_supplicant          # arm64-v8a
├── wpa_supplicant_32       # armeabi-v7a
├── wpa_cli                 # arm64-v8a
├── wpa_cli_32              # armeabi-v7a
├── pixiewps                # arm64-v8a
├── pixiewps_32             # armeabi-v7a
├── libnl-3.so              # arm64-v8a
├── libnl-genl-3.so         # arm64-v8a
├── libnl-route-3.so        # arm64-v8a
├── libnl-3.so-32           # armeabi-v7a
├── libnl-genl-3.so-32      # armeabi-v7a
├── libnl-route-3.so-32     # armeabi-v7a
└── wpa_supplicant.conf     # configuración mínima (ver abajo)
```

## 1. wpa_supplicant y wpa_cli

Fuente oficial: <https://w1.fi/wpa_supplicant/> (licencia BSD-3-Clause).

```bash
# Descargar y descomprimir
wget https://w1.fi/releases/wpa_supplicant-2.11.tar.gz
tar xzf wpa_supplicant-2.11.tar.gz
cd wpa_supplicant-2.11/wpa_supplicant

# Copiar defconfig y habilitar control por socket (necesario para wpa_cli)
cp defconfig .config
echo "CONFIG_CTRL_IFACE=y" >> .config
echo "CONFIG_CTRL_IFACE_UNIX=y" >> .config

# Compilar para arm64-v8a (Android NDK)
export ANDROID_NDK=/ruta/a/android-ndk
export PATH=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
export CC=aarch64-linux-android31-clang
export CXX=aarch64-linux-android31-clang++
export AR=aarch64-linux-android-ar
export STRIP=aarch64-linux-android-strip
export CFLAGS="-I$ANDROID_NDK/sysroot/usr/include -DANDROID -fPIC"
export LDFLAGS="-L$ANDROID_NDK/sysroot/usr/lib/aarch64-linux-android"

make -C wpa_supplicant -j$(nproc)
make -C wpa_cli -j$(nproc)

# Copiar resultados a assets/wifi/
cp wpa_supplicant/wpa_supplicant ../../app/src/main/assets/wifi/wpa_supplicant
cp wpa_cli/wpa_cli ../../app/src/main/assets/wifi/wpa_cli

# Compilar para armeabi-v7a
export CC=armv7a-linux-androideabi31-clang
export CXX=armv7a-linux-androideabi31-clang++
export AR=arm-linux-androideabi-ar
export STRIP=arm-linux-androideabi-strip
export CFLAGS="-I$ANDROID_NDK/sysroot/usr/include -DANDROID -fPIC"
export LDFLAGS="-L$ANDROID_NDK/sysroot/usr/lib/arm-linux-androideabi"

make clean
make -C wpa_supplicant -j$(nproc)
make -C wpa_cli -j$(nproc)

cp wpa_supplicant/wpa_supplicant ../../app/src/main/assets/wifi/wpa_supplicant_32
cp wpa_cli/wpa_cli ../../app/src/main/assets/wifi/wpa_cli_32
```

## 2. pixiewps

Fuente oficial: <https://github.com/wiire-a/pixiewps> (licencia GPL-3.0-or-later).

```bash
git clone https://github.com/wiire-a/pixiewps.git
cd pixiewps/src

# arm64-v8a
export CC=aarch64-linux-android31-clang
make -j$(nproc)
cp pixiewps ../../app/src/main/assets/wifi/pixiewps

# armeabi-v7a
export CC=armv7a-linux-androideabi31-clang
make clean
make -j$(nproc)
cp pixiewps ../../app/src/main/assets/wifi/pixiewps_32
```

## 3. libnl (librerías compartidas de wpa_supplicant)

wpa_supplicant depende de libnl. Fuentes: <https://github.com/thom311/libnl> (LGPL-2.1-or-later).

Compila `libnl-3.so`, `libnl-genl-3.so`, `libnl-route-3.so` para ambas arquitecturas y
colócalas en `assets/wifi/` con los nombres indicados arriba (incluyendo los sufijos `_32`).

## 4. wpa_supplicant.conf

Crea `app/src/main/assets/wifi/wpa_supplicant.conf` con:

```
ctrl_interface_group=wifi
```

## 5. Permisos y SELinux

- La app ejecuta los binarios vía `su`. El dispositivo debe estar rooteado.
- En Android 10+ (API 29+) SELinux puede bloquear la creación de sockets de control
  en `/data/vendor/wifi/wpa/`. El código usa `/data/vendor/wifi/wpa/amwf` (API >= 28)
  o `/data/misc/wifi/amwf` (API < 28) como directorio de control.
- Si el ataque falla con "socket not created", revisa los logs (logcat) y la política
  SELinux de tu dispositivo/kernel.

## 6. Verificación

Tras empaquetar los assets, instala la app en un dispositivo rooteado, abre la
pantalla "Auditoría WPS Pixie Dust" y comprueba que aparezcan interfaces disponibles.
Si faltan binarios, la app mostrará el mensaje correspondiente en la consola.

## Licencia de los binarios

| Binario | Licencia | Implicación para AmWF (GPL-3.0) |
|---------|----------|---------------------------------|
| pixiewps | GPL-3.0-or-later | Compatible con GPL-3.0 de AmWF |
| wpa_supplicant / wpa_cli | BSD-3-Clause | Compatible |
| libnl | LGPL-2.1-or-later | Compatible |
