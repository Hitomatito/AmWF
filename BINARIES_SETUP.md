# BINARIES_SETUP — Binarios nativos para el módulo Pixie Dust

El módulo Pixie Dust de AmWF requiere varios binarios compilados para Android ARM.
**Pixiewps ya está compilado e incluido** en `app/src/main/assets/wifi/`.
Los binarios de `wpa_supplicant`, `wpa_cli` y `libnl` deben obtenerse por separado.

## Estructura esperada en assets

```
app/src/main/assets/wifi/
├── wpa_supplicant          # arm64-v8a (REQUERIDO - falta compilar)
├── wpa_supplicant_32       # armeabi-v7a (REQUERIDO - falta compilar)
├── wpa_cli                 # arm64-v8a (REQUERIDO - falta compilar)
├── wpa_cli_32              # armeabi-v7a (REQUERIDO - falta compilar)
├── pixiewps                # arm64-v8a ✅ YA INCLUIDO
├── pixiewps_32             # armeabi-v7a ✅ YA INCLUIDO
├── libnl-3.so              # arm64-v8a (opcional - solo para nl80211)
├── libnl-genl-3.so         # arm64-v8a (opcional)
├── libnl-route-3.so        # arm64-v8a (opcional)
├── libnl-3.so-32           # armeabi-v7a (opcional)
├── libnl-genl-3.so-32      # armeabi-v7a (opcional)
├── libnl-route-3.so-32     # armeabi-v7a (opcional)
└── wpa_supplicant.conf     # ✅ YA INCLUIDO
```

## 1. pixiewps (ya compilado ✅)

Los binarios de pixiewps ya están compilados y ubicados en:
- `app/src/main/assets/wifi/pixiewps` — ELF 64-bit ARM aarch64, Android API 24
- `app/src/main/assets/wifi/pixiewps_32` — ELF 32-bit ARM, Android API 24

Licencia: GPL-3.0-or-later (compatible con AmWF GPL-3.0)

Si necesitas recompilarlos:
```bash
git clone https://github.com/wiire-a/pixiewps.git
cd pixiewps

# arm64-v8a
export ANDROID_NDK=/ruta/a/android-ndk
export PATH=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
export CC=aarch64-linux-android24-clang
make LIBS="" OPENSSL=0
aarch64-linux-android-strip pixiewps
cp pixiewps ../app/src/main/assets/wifi/pixiewps

# armeabi-v7a
make clean
export CC=armv7a-linux-androideabi24-clang
make LIBS="" OPENSSL=0
arm-linux-androideabi-strip pixiewps
cp pixiewps ../app/src/main/assets/wifi/pixiewps_32
```

> Nota: En Android, `pthread` está integrado en `libc`, no hay `libpthread` separada.
> Usa `LIBS=""` para evitar errores de enlazador.

## 2. wpa_supplicant y wpa_cli (FALTAN — necesitan compilarse)

Intenté compilar wpa_supplicant 2.11, 2.6 y 1.0 con el NDK r27 de Android
(clang 18), pero todas las versiones fallan debido a incompatibilidades entre
el código C antiguo de wpa_supplicant y clang 18 (errores de struct incompletos,
definiciones faltantes, etc.).

### Opción A: Buscar binarios precompilados

Busca en GitHub repositorios con "wpa_supplicant android binary" o
"wpa_supplicant arm64 apk". Algunos proyectos empaquetan wpa_supplicant
dentro de aplicaciones de diagnóstico WiFi.

### Opción B: Extraer de una ROM de Android con root

En muchos dispositivos Android rooteados, wpa_supplicant ya está instalado en el
sistema:
```bash
# En el dispositivo rooteado
adb shell su -c "which wpa_supplicant"
adb shell su -c "which wpa_cli"
# Suele estar en /system/bin/, /system/xbin/ o /vendor/bin/
adb pull /system/bin/wpa_supplicant ./wpa_supplicant_arm64
adb pull /system/bin/wpa_cli ./wpa_cli_arm64
```

Luego cópialos a `app/src/main/assets/wifi/`.

### Opción C: Compilar con gcc en lugar de clang

El NDK de Android r27 solo incluye clang. Si tienes acceso a un compilador
gcc cruzado para Android (arm-linux-androideabi-gcc), wpa_supplicant
compilará más fácilmente:

```bash
cd wpa_supplicant-2.6/wpa_supplicant
cp defconfig .config
echo "CONFIG_DRIVER_NL80211=y" >> .config
echo "CONFIG_CTRL_IFACE=y" >> .config
echo "CONFIG_WPS=y" >> .config

make CC=arm-linux-androideabi-gcc \
     CFLAGS="-DANDROID -fPIE -fPIC -O2 -w" \
     LDFLAGS="-pie"
```

## 3. libnl (opcional — solo para nl80211 driver)

libnl es necesario solo si usas el driver `nl80211` en wpa_supplicant.
Si usas el driver `wext` (más antiguo, no necesita libnl), no es obligatorio.

Si necesitas compilar libnl, la versión 3.9.0 falla con autotools en el NDK
de Android debido a que `pthread` no es una biblioteca separada en Android
(está integrada en libc). Intenta con versiones más antiguas (libnl 1.x o 2.x)
que son más simples.

## 4. wpa_supplicant.conf ✅ YA INCLUIDO

Ya está en `app/src/main/assets/wifi/wpa_supplicant.conf` con:
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

Tras empaquetar todos los binarios en `app/src/main/assets/wifi/`, instala la app
en un dispositivo rooteado, abre la pantalla "Auditoría WPS Pixie Dust" y comprueba
que aparezcan interfaces disponibles. Si faltan binarios, la app mostrará el
mensaje correspondiente en la consola.

## Licencia de los binarios

| Binario | Licencia | Implicación para AmWF (GPL-3.0) |
|---------|----------|---------------------------------|
| pixiewps | GPL-3.0-or-later | Compatible ✅ |
| wpa_supplicant / wpa_cli | BSD-3-Clause | Compatible ✅ |
| libnl | LGPL-2.1-or-later | Compatible ✅ |

## Estado actual

```
app/src/main/assets/wifi/
├── pixiewps              ✅ (arm64-v8a, compilado)
├── pixiewps_32           ✅ (armeabi-v7a, compilado)
├── wpa_supplicant.conf   ✅
├── wpa_supplicant        ❌ (falta compilar o conseguir)
├── wpa_supplicant_32     ❌ (falta compilar o conseguir)
├── wpa_cli               ❌ (falta compilar o conseguir)
├── wpa_cli_32            ❌ (falta compilar o conseguir)
└── libnl-*               ⚠️  (opcional, solo para nl80211)
```