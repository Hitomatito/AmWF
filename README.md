# AmWF - Android WiFi Monitor Mode Controller

<div align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=flat-square&logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/UI-Material%20Design%203-red?style=flat-square&logo=materialdesign" alt="UI">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License">
</div>

> Aplicación Android para activar y desactivar el modo monitor WiFi en dispositivos Qualcomm Snapdragon con root.

## 📱 Descripción

AmWF es una herramienta que permite controlar el modo monitor de la interfaz WiFi en dispositivos Android con chipset Qualcomm Snapdragon. El modo monitor es necesario para realizar análisis de redes inalámbricas y capturar handshakes WPA/WPA2.

## ✨ Características

- 🔘 **Control de Modo Monitor** - Activa/Desactiva el modo monitor WiFi con un solo toque
- 🔍 **Verificación de Compatibilidad** - Detecta automáticamente si el dispositivo es compatible
- 📊 **Información del Sistema** - Muestra chipset, driver WiFi, estado de root y archivo con_mode
- 🌐 **Soporte Multilingüe** - Español e Inglés
- 🎨 **Material Design 3** - Interfaz moderna y elegante
- 🔒 **Seguridad** - Verificación de root y restricciones SELinux

## 📋 Requisitos

### Requisitos del Dispositivo

| Requisito | Mínimo | Recomendado |
|-----------|--------|-------------|
| Android | 10 (API 29) | 13+ (API 33) |
| Root | Magisk / KernelSU / APatch / SuperSU | Magisk |
| Chipset | Qualcomm Snapdragon | Snapdragon 855+ |
| Arquitectura | aarch64 (ARM64) | aarch64 |

### Requisitos del Sistema (Desarrollo)

| Requisito | Versión |
|------------|---------|
| Android Studio | 2024.0+ |
| Kotlin | 1.9+ |
| Gradle | 8.0+ |
| JDK | 17+ |

## 🚀 Instalación

### Desde APK (Recomendado)

1. Descarga la última versión desde [Releases](../../releases)
2. Instala el APK en tu dispositivo
3. Activa el modo monitor desde la app

### Desde Código Fuente

```bash
# Clonar repositorio
git clone https://github.com/your-repo/AmWF.git
cd AmWF

# Abrir en Android Studio
# File > Open > seleccionar carpeta del proyecto

# Compilar
./gradlew assembleDebug

# El APK estará en: app/build/outputs/apk/debug/app-debug.apk
```

## 📖 Uso

### Interfaz Principal

```
┌────────────────────────────────────┐
│ AmWF                    [🌐 ES]   │  ← Selector de idioma
│ Control de Modo Monitor WiFi       │
│                                    │
│ ┌────────────────────────────────┐│
│ │ ● Compatibilidad del Dispositivo ││
│ │   ✓ Listo para usar            ││
│ │────────────────────────────────││
│ │ 🔲 Snapdragon 855/855+         ││
│ │ 🛡️ Qualcomm WCNSS             ││
│ │ 🔒 Magisk v27.0               ││
│ │ 📁 con_mode                    ││
│ └────────────────────────────────┘│
│                                    │
│ ┌────────────────────────────────┐│
│ │ ○ Modo WiFi                    ││
│ │   Modo Normal                  ││
│ │────────────────────────────────││
│ │ 📶 Interface: wlan0            ││
│ │    MAC: bc:6a:d1:b6:21:6c     ││
│ └────────────────────────────────┘│
│                                    │
│    [    ACTIVAR MONITOR    ]       │
│                                    │
└────────────────────────────────────┘
```

### Indicadores de Estado

| Estado | Color | Significado |
|--------|-------|-------------|
| 🟢 Verde | Cyan | Modo Monitor activo / Compatible |
| 🟠 Naranja | Coral | Modo Normal / No compatible |
| ⚪ Gris | Neutral | Error / Verificando |

### Pasos para Activar Modo Monitor

1. **Verificar Compatibilidad** - La app verifica automáticamente al iniciar
2. **Otorgar Permisos Root** - Aprobar la solicitud de Magisk/KernelSU
3. **Presionar "ACTIVAR MONITOR"** - El botón cambiará a "VOLVER A NORMAL"
4. **¡Listo!** - La antena está en modo monitor

### Cambiar Idioma

1. Presiona el botón con el código de idioma (ES/EN) en la esquina superior derecha
2. La app se reiniciará con el nuevo idioma seleccionado

## 🔧 Detalles Técnicos

### Método de Activación

La app utiliza el método `con_mode` de Qualcomm para activar el modo monitor:

```bash
# Desactivar interfaz
ip link set wlan0 down

# Activar modo monitor (con_mode = 4)
echo 4 > /sys/module/wlan/parameters/con_mode

# Reactivar interfaz
ip link set wlan0 up

# Verificar modo
iw dev wlan0 info
```

### Valores de con_mode

| Valor | Modo | Descripción |
|-------|-------|-------------|
| 0 | STA | Station (Normal) |
| 1 | Monitor | Modo monitor raw |
| 4 | AP | Access Point |
| 5 | P2P | Wi-Fi Direct |

### Verificación de Compatibilidad

La app verifica:

1. **Acceso Root** - Detecta Magisk, KernelSU, APatch, SuperSU, phh-su
2. **Chipset** - Verifica que sea Qualcomm Snapdragon
3. **Driver** - Confirma que `/sys/module/wlan/parameters/con_mode` existe
4. **Escritura** - Prueba escribir en con_mode

### Detección de Chipset

La app detecta el chipset usando múltiples fuentes:

- `ro.hardware` - Nombre interno del SoC
- `/proc/cpuinfo` - Información del CPU (Hardware, CPU part)
- `ro.product.board` - Plataforma de la placa

Tabla de identificación por CPU part (hex):

| CPU Part | Snapdragon |
|---------|------------|
| 0x070 | 855 |
| 0x072 | 865/870 |
| 0x080 | 888 |
| 0x081 | 8 Gen 1 |
| 0x084 | 8 Gen 2 |
| 0x086 | 8 Gen 3 |

## 📂 Estructura del Proyecto

```
AmWF/
├── app/
│   ├── src/main/
│   │   ├── java/com/hitomatito/amwf/
│   │   │   ├── MainActivity.kt           # Actividad principal
│   │   │   ├── MonitorModeManager.kt      # Gestión de modo monitor
│   │   │   ├── DeviceCompatibilityChecker.kt  # Verificación de compatibilidad
│   │   │   └── LocaleHelper.kt           # Helper de idiomas
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   ├── item_row_*.xml        # Items de información
│   │   │   ├── drawable/                 # Iconos vectoriales
│   │   │   ├── values/                   # Strings español
│   │   │   └── values-en/               # Strings inglés
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 🛠️ Tecnologías

| Tecnología | Propósito |
|------------|-----------|
| Kotlin | Lenguaje principal |
| Material Design 3 | Sistema de diseño UI |
| Coroutines | Programación asíncrona |
| SharedPreferences | Persistencia de configuración |
| View Binding | Acceso a vistas tipado |

## ⚠️ Limitaciones

- **Solo Qualcomm Snapdragon** - No funciona en MediaTek, Exynos, etc.
- **Requiere Root** - Sin root no es posible modificar el modo WiFi
- **SELinux** - Algunas configuraciones pueden bloquear el acceso
- **No todas las redes** - Captura de handshakes depende de muchos factores
- **Modo Monitor Pasivo** - El método `con_mode` solo permite escaneo pasivo en la mayoría de dispositivos
- **Sin Inyección de Paquetes** - Generalmente no es posible inyectar/tranmitir paquetes en este modo
- **Captura Limitada** - Algunos drivers solo permiten captura pasiva sin modificación de tramas

### Sobre el Modo Monitor

El método `con_mode` de Qualcomm tiene limitaciones importantes:

| Característica | Disponibilidad |
|----------------|----------------|
| Escaneo pasivo | ✅ Generalmente disponible |
| Captura de tramas | ⚠️ Depende del driver |
| Inyección de paquetes | ❌ Generalmente no disponible |
| Transmisión | ❌ No disponible |

La app incluye una verificación automática de estas capacidades. Algunos dispositivos con drivers específicos pueden soportar funcionalidades adicionales.

## 🔒 Seguridad

- Los datos se almacenan únicamente en SharedPreferences local
- No se envían datos a ningún servidor
- No se requiere internet para funcionar
- Root es necesario únicamente para modificar parámetros del kernel

## 📄 Licencia

Este proyecto está bajo la Licencia MIT. Ver el archivo [LICENSE](LICENSE) para más detalles.

## 🤝 Contribuir

1. Fork el repositorio
2. Crea una rama para tu feature (`git checkout -b feature/nueva-funcion`)
3. Commit tus cambios (`git commit -m 'Agregar nueva función'`)
4. Push a la rama (`git push origin feature/nueva-funcion`)
5. Abre un Pull Request

## 📞 Soporte

- 🐛 **Bugs**: Abre un [Issue](../../issues)
- 💡 **Features**: Solicita en [Discussions](../../discussions)
- 📖 **Documentación**: Mejora este README

---

<div align="center">
  <p>Hecho con ❤️ para la comunidad de Android</p>
  <p>© 2024 AmWF</p>
</div>
