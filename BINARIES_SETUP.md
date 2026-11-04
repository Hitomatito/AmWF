# BINARIES_SETUP — Binarios nativos para el módulo Pixie Dust

El módulo Pixie Dust de AmWF requiere varios binarios compilados para Android
ARM. Para arm64-v8a (la arquitectura del Redmi Note 10 Pro / `sweet`) están
incluidos: `pixiewps`, `wpa_supplicant`, `wpa_cli` y las dos librerías de
libnl que necesita el supplicant.

## Estructura actual en assets

```
app/src/main/assets/wifi/
├── pixiewps                # arm64-v8a   ✅ compilado con NDK r27
├── pixiewps_32             # armeabi-v7a ✅ compilado con NDK r27
├── wpa_supplicant          # arm64-v8a   ✅ wpa_supplicant 2.11 propio
├── wpa_supplicant_32       # armeabi-v7a ❌ no incluido (32-bit no soportado)
├── wpa_cli                 # arm64-v8a   ✅ compilado desde wpa_supplicant 2.11
├── wpa_cli_32              # armeabi-v7a ♻️ asset precompilado conservado (el script no lo regenera)
├── wpa_supplicant.conf     # configuración base del supplicant
├── libnl-3.so.200          # arm64-v8a   ✅ libnl 3.2.25
└── libnl-genl-3.so.200     # arm64-v8a   ✅ libnl 3.2.25 (genl)
```

Cuando `NativeBinaries.archSuffix()` resuelve `"_32"` (dispositivos de 32-bit),
`missingCriticalBinaries()` reportará que falta `wpa_supplicant_32` y la
auditoría se detendrá con un mensaje claro. El supplicant empaquetado solo se
compila para arm64-v8a.

## 1. Por qué un wpa_supplicant propio

El `wpa_supplicant` de Android está compilado con `CONFIG_ANDROID_LOG`. En esa
variante, `_wpa_hexdump()` recorta los volcados a **32 bytes** y los envía a
logcat. Pixie Dust necesita los seis campos completos del M3, incluida la clave
pública DH de **192 bytes** (384 caracteres hex), así que el volcado truncado
no sirve.

El binario de AmWF se compila **sin** `CONFIG_ANDROID_LOG` y **con**
`CONFIG_DEBUG_FILE=y`. Sin `-f`, el debug (incluidos los hexdump completos)
sale por **stdout**, que es exactamente lo que lee `M3Parser`. Por eso
`NativeBinaries.missingCriticalBinaries()` **no acepta** el
`wpa_supplicant` del sistema como sustituto: aunque exista, no puede entregar
las claves DH completas. El formato es:

```
WPS: Enrollee Nonce - hexdump(len=16): 01 02 ...
WPS: DH own Public Key - hexdump(len=192): ...
WPS: DH peer Public Key - hexdump(len=192): ...
WPS: AuthKey - hexdump(len=32): ...
WPS: E-Hash1 - hexdump(len=32): ...
WPS: E-Hash2 - hexdump(len=32): ...
```

`-K` es imprescindible para que `wpa_hexdump_key()` imprima `AuthKey` en lugar
de `[REMOVED]`.

**Fallback a binarios del sistema (`wpa_cli` y `pixiewps`).** A diferencia del
supplicant, `wpa_cli` y `pixiewps` **sí** pueden resolverse desde el sistema
cuando el asset empaquetado no está disponible: `resolveBinaryPath()` busca
primero en `filesDir` (donde `ensure()` copia los assets) y luego en las rutas
`/system/bin`, `/vendor/bin`, `/vendor/bin/hw`, `/system/xbin` y `/odm/bin`,
con el nombre sin sufijo de arquitectura. Si no se encuentra en ninguna, el
binario se reporta como faltante con normalidad. El fallback es intencional:
`wpa_cli` solo transmite comandos de texto al supplicant y `pixiewps` trabaja
offline sobre los datos ya capturados; ninguno de los dos toca el hexdump WPS.
El `wpa_supplicant` del sistema queda excluido de este fallback por el motivo
explicado arriba. Consecuencia visible: la fila **Binarios Pixie Dust** puede
aparecer como disponible aunque falte el asset empaquetado de `wpa_cli` o
`pixiewps`, porque se está usando el del sistema.

## 2. Compilación reproducible

El script `scripts/build-wifi-binaries.sh` compila los binarios de **arm64-v8a**
(pixiewps, wpa_supplicant, wpa_cli y libnl). Para **armeabi-v7a** solo compila
`pixiewps_32`: el supplicant de 32 bits no está soportado y `wpa_cli_32` se
conserva como asset precompilado, sin regenerarse por el script. Requiere las
fuentes:

```
../pixiewps-master/
../wpa_supplicant-2.11/
../libnl-3.2.25/
```

y el NDK r27 (o posterior):

```bash
export ANDROID_NDK=/opt/android-sdk/ndk/27.0.12077973
./scripts/build-wifi-binaries.sh arm64-v8a
```

Pasos que realiza para el supplicant:

1. Compila **libnl 3.2.25** para Android con `--disable-static --enable-shared`.
   Aplica `scripts/patches/libnl-3.2.25-wpa2.11.patch`, que añade `NLA_S8` y
   `nla_get_s8()` (wpa_supplicant 2.11 los usa y libnl 3.2.25 aún no los tenía).
   Los linker scripts `libpthread.so`/`librt.so` apuntan a `libc`, porque bionic
   integra pthread/rt y el sysroot del NDK no trae esas `-l`.
2. Copia `defconfig` a `.config` y desactiva DBus, syslog, AP, P2P, DPP, SAE,
   EAP-PWD, drivers wired/macsec/wext y otras características que no necesita
   una estación WPS. Activa TLS interno, libtommath, `CONFIG_NO_RANDOM_POOL`,
   `CONFIG_DEBUG_FILE`, WPS, nl80211 y libnl32.
3. Añade un directorio de includes filtrado (`build-include/netlink`) para que
   la copia antigua de `<linux/if.h>` que trae libnl no tape la de bionic. Sin
   esto, el compilador ve `struct ifreq` incompleto.
4. Crea `private/android_filesystem_config.h` con los AID que usan
   `wpa_ctrl.c` y `os_unix.c`.
5. Compila `wpa_supplicant` y `wpa_cli` con API 24, los strip-ea y los copia a
   `assets/wifi/`.

## 3. Runtime: librerías y LD_LIBRARY_PATH

`SupplicantProcesses` exporta `LD_LIBRARY_PATH=<filesDir>` en el comando que
lanza `wpa_supplicant`, de modo que el dynamic linker encuentra
`libnl-3.so.200` y `libnl-genl-3.so.200` en el mismo directorio. `libc.so`,
`liblog.so`, `libm.so`, `libdl.so` y el resto de bionic provienen del sistema
del dispositivo y no se empaquetan.

`wpa_cli` solo enlaza con bionic; no necesita libnl.

## 4. wpa_supplicant.conf

El asset contiene la configuración base:

```
ctrl_interface_group=wifi
update_config=1
ap_scan=1
```

`SupplicantProcesses.stageConfig()` (invocado por el runner) descarta las líneas
`ctrl_interface*` y genera un config runtime con la ruta real del socket:

```
ctrl_interface=DIR=/data/vendor/wifi/amwf GROUP=wifi
update_config=1
ap_scan=1
```

El config se envía por stdin de `su` porque el dominio SELinux de `su` no puede
leer `filesDir`. `update_config=1` permite que `save_config` persista el PSK
tras la provisión WPS.

## 5. Permisos y SELinux

- La app ejecuta los binarios vía `su`. El dispositivo debe estar rooteado.
- El directorio de control usa `/data/vendor/wifi/amwf` en Android 9+
  (API 28+, la ruta concedida en la política SELinux principal para
  dispositivos que estrenan P o posterior, y la única vigente en Android 10+).
  En versiones anteriores (o dispositivos que **actualizaron** a P en lugar de
  estrenarlo, que conservan la ruta heredada) el código usa
  `/data/misc/wifi/amwf` (`SDK_INT < 28`). La distinción depende de qué estrenó
  el dispositivo, no solo de la versión que reporta el sistema; si el
  supplicant falla con "socket not created", prueba el directorio contrario.
- El directorio de control se crea y se etiqueta con el contexto del proceso
  `su`; el socket se crea correctamente siempre que el config declare
  `ctrl_interface`. Si el ataque falla con "socket not created", revisa el
  comando y el config runtime antes de culpar a SELinux.
- No se debe borrar `/data/vendor/wifi/wpa/sockets`, que pertenece al
  supplicant del sistema.

## 6. Verificación

Instala la app en un dispositivo rooteado, comprueba "Compatibilidad del
Dispositivo" (fila **Binarios Pixie Dust**) y lanza "Auditoría WPS Pixie Dust".
El log de la app debe mostrar `wpa_supplicant ctrl interface ready (PONG)` y,
tras `wps_reg`, las seis líneas `WPS: ... - hexdump(len=...)` completas.

## Licencia de los binarios

| Binario | Licencia | Implicación para AmWF (GPL-3.0) |
|---------|----------|---------------------------------|
| pixiewps | GPL-3.0-or-later | Compatible ✅ |
| wpa_supplicant / wpa_cli | BSD-3-Clause / GPL-2.0 | Compatible ✅ (rama BSD con atribución) |
| libnl-3 / libnl-genl-3 | LGPL-2.1 | Compatible ✅ (enlace dinámico) |

Ver detalles de procedencia, fuentes y parches en `THIRD-PARTY-NOTICES.md`.
