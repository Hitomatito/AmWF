# BINARIES_SETUP — Binarios nativos para el módulo Pixie Dust

El módulo Pixie Dust de AmWF requiere varios binarios compilados para Android ARM.
**Pixiewps y wpa_supplicant.conf ya están incluidos**; los demás binarios pueden
compilarse con el script de este repositorio o obtenerse por separado.

## Estructura esperada en assets

```
app/src/main/assets/wifi/
├── pixiewps                # arm64-v8a   ✅ INCLUIDO (compilado con NDK r27)
├── pixiewps_32             # armeabi-v7a ✅ INCLUIDO (compilado con NDK r27)
├── wpa_supplicant.conf     # ✅ INCLUIDO
├── wpa_supplicant          # arm64-v8a   ❌ (falta — ver abajo)
├── wpa_supplicant_32       # armeabi-v7a ❌ (falta — ver abajo)
├── wpa_cli                 # arm64-v8a   ❌ (falta — ver abajo)
├── wpa_cli_32              # armeabi-v7a ❌ (falta — ver abajo)
└── libnl-*                 # ⚠️  Opcional: solo si usas driver nl80211
```

## 1. Compilar todos los binarios (recomendado)

El script `scripts/build-wifi-binaries.sh` compila **pixiewps, wpa_supplicant y
wpa_cli** para una arquitectura dada, eliminando la necesidad de repetir los
bloques de `export`/`make`/`cp` por cada arquitectura.

```bash
# arm64-v8a (dispositivos modernos)
./scripts/build-wifi-binaries.sh arm64-v8a

# armeabi-v7a (dispositivos antiguos)
./scripts/build-wifi-binaries.sh armeabi-v7a
```

Los binarios se escriben directamente en `app/src/main/assets/wifi/`.

Requisitos:
- `ANDROID_NDK` (o `ANDROID_NDK_HOME`) apuntando a tu NDK de Android.
- Fuentes de `wpa_supplicant-2.6/` y `pixiewps-master/` en el directorio padre
  (o define `WPA_DIR` y `PIXIEWPS_DIR` antes de ejecutar).

> Nota: En Android, `pthread` está integrado en `libc`; no existe
> `libpthread` separada. El script ya usa `LIBS=""` para evitar errores
> de enlazador.

## 2. wpa_supplicant y wpa_cli (obtener por separado)

Si no puedes compilar con el script, consigue los binarios por:

- **Opción A (precompilados):** Busca en GitHub "wpa_supplicant android binary"
  o dentro de aplicaciones de diagnóstico WiFi.
- **Opción B (dispositivo rooteado):** Extrae de un Android rooteado:
  ```bash
  adb shell su -c "which wpa_supplicant wpa_cli"
  adb pull /system/bin/wpa_supplicant ./wpa_supplicant_arm64
  adb pull /system/bin/wpa_cli ./wpa_cli_arm64
  ```
  Luego cópialos a `app/src/main/assets/wifi/`.
- **Opción C (compilar con gcc):** wpa_supplicant compila más fácil con
  `arm-linux-androideabi-gcc` que con clang 18 del NDK.

## 3. libnl (opcional — solo para driver nl80211)

libnl solo es necesario si usas el driver `nl80211` en wpa_supplicant.
Con el driver `wext` (más antiguo, no necesita libnl) no es obligatorio.

## 4. wpa_supplicant.conf ✅ YA INCLUIDO

Ya está en `app/src/main/assets/wifi/wpa_supplicant.conf` con:
```
ctrl_interface_group=wifi
```

## 5. Permisos y SELinux

- La app ejecuta los binarios vía `su`. El dispositivo debe estar rooteado.
- En Android 10+ (API 29+) SELinux puede bloquear la creación de sockets de control
  en `/data/vendor/wifi/wpa/`. El código usa `/data/vendor/wifi/wpa/amwf` (API >= 28)
  o `/data/misc/wifi/amwf` (API < 28).
- Si el ataque falla con "socket not created", revisa logcat y la política SELinux.

## 6. Verificación

Instala la app en un dispositivo rooteado, abre "Auditoría WPS Pixie Dust" y
comprueba que aparezcan interfaces disponibles. Si faltan binarios, la consola
mostrará el mensaje correspondiente.

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
├── wpa_supplicant        ❌ (falta — usa build-wifi-binaries.sh)
├── wpa_supplicant_32     ❌ (falta — usa build-wifi-binaries.sh)
├── wpa_cli               ❌ (falta — usa build-wifi-binaries.sh)
├── wpa_cli_32            ❌ (falta — usa build-wifi-binaries.sh)
└── libnl-*               ⚠️  (opcional, solo para nl80211)
```