# DESFire EV3 NDEF + SDM Writer (Android / Kotlin)

Aplicación Android para leer y escribir en tarjetas **MIFARE DESFire EV3 (MF3D43)**,
permitiendo configurar URLs NDEF con **SDM (Secure Dynamic Messaging)** activado.

---

## Características

- ✅ Detección automática de DESFire EV3 via NFC foreground dispatch
- ✅ Escritura de NDEF URI record (URL personalizable)
- ✅ Activación de SDM via `ChangeFileSettings` APDU
- ✅ Opciones SDM configurables: UID Mirror, Counter Mirror, Encrypted UID, SDMMAC
- ✅ Modo lectura para inspeccionar el tag
- ✅ Log en pantalla de todos los pasos APDU
- ✅ Compatible con TapLinx 5.0 (NxpNfcAndroidLib)

---

## Requisitos

- Android Studio Hedgehog o superior
- Android SDK API 33 (targetSdk)
- Java 8 o superior
- Dispositivo físico Android con NFC (no emulador)

---

## Estructura del proyecto

```
DesfireSDM/
├── app/
│   ├── libs/
│   │   └── NxpNfcAndroidLib-release-protected.aar   ← TapLinx library
│   ├── src/main/
│   │   ├── kotlin/com/example/desfiresdm/
│   │   │   └── MainActivity.kt                       ← Toda la lógica principal
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   ├── values/themes.xml
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Cómo compilar

### En Android Studio

1. Abre Android Studio → **File > Open** → selecciona la carpeta `DesfireSDM/`
2. Espera a que Gradle sincronice (descargará dependencias automáticamente)
3. Conecta tu dispositivo Android con NFC
4. Haz clic en **Run ▶**

### Desde línea de comandos

```bash
cd DesfireSDM
./gradlew assembleDebug
# APK generada en: app/build/outputs/apk/debug/app-debug.apk
```

Para instalar directamente:
```bash
./gradlew installDebug
```

---

## Cómo usar la aplicación

### Escritura NDEF + SDM

1. Escribe (o mantén por defecto) una URL en el campo de texto
2. Selecciona las opciones SDM deseadas:
   - **UID Mirror**: incluir UID del tag encriptado en la URL
   - **Counter Mirror**: incluir contador de lecturas en la URL
   - **Encrypted UID**: usar ENCPICCData (UID + counter encriptados juntos)
   - **SDM MAC**: añadir CMAC-AES al final de la URL para verificación backend
3. Pulsa **"ESCRIBIR NDEF + SDM EN TAG"**
4. Acerca el tag DESFire EV3 al dispositivo

La app ejecutará automáticamente:
- Selección del PICC (AID 0x000000)
- Autenticación con clave por defecto
- Creación de la aplicación NDEF (si no existe)
- Creación del archivo NDEF (file 02)
- Escritura de la URL con placeholders SDM
- Activación SDM via `ChangeFileSettings`

### Lectura

1. Pulsa **"LEER TAG"**
2. Acerca el tag

---

## Fundamento técnico SDM

### ¿Qué es SDM?

**Secure Dynamic Messaging (SDM)** es una característica del DESFire EV3 (y NTAG 424 DNA)
que modifica dinámicamente el contenido del mensaje NDEF en cada lectura, insertando:

- **ENCPICCData** (16 bytes = 32 chars hex): UID del tag + contador de lecturas,
  encriptados con AES-128 usando la sesión SDM
- **SDMMAC** (8 bytes = 16 chars hex): CMAC-AES truncado para verificar la autenticidad
  del mensaje en el backend

El resultado es que cada vez que un smartphone lee el tag, la URL que abre el navegador
contiene datos únicos y verificables, imposibles de clonar sin la clave AES.

### Flujo de escritura

```
Smartphone ←→ DESFire EV3
    │
    ├─ SelectApplication(0x000000)
    ├─ Authenticate(key0, 2KTDES/AES)
    ├─ CreateApplication(AID, AES keys)
    ├─ SelectApplication(AID)
    ├─ AuthenticateEV2First(key0, AES)
    ├─ CreateFile(file02, StdData, plain)
    ├─ WriteNDEF(URL con placeholders)
    └─ ChangeFileSettings(file02, SDM enabled)
```

### Byte de opciones SDM (`SDMOptions`)

| Bit | Descripción                      |
|-----|----------------------------------|
| 7   | UID Mirror (espejo de UID)       |
| 6   | SDMReadCtr mirror (contador)     |
| 5   | SDMReadCtrLimit (límite)         |
| 4   | SDMENCFileData (UID+Ctr cifrados)|
| 0   | ASCII Encoding (hex en URL)      |

### Offsets SDM en el archivo NDEF

El comando `ChangeFileSettings` incluye offsets en bytes dentro del archivo NDEF
donde el tag debe insertar los datos dinámicos:

```
NDEF File Binary Layout:
┌─────────────────────────────────────────────────────────┐
│ NLEN[2] │ TLV(0x03) │ LEN │ NDEF Record │ Terminator   │
└─────────────────────────────────────────────────────────┘
                              │
                       URI Record:
               ┌──────────────────────────────────────────┐
               │ 0xD1 │ 0x01 │ Len │ "U" │ 0x04 │ URL... │
               └──────────────────────────────────────────┘
                                              │
                                    https://example.com/?
                                    e=[ENCPICCData(32)]&
                                    m=[SDMMAC(16)]
                                       ↑                ↑
                               ENCPICCDataOffset   SDMMACOffset
```

### Comando ChangeFileSettings (INS 0x5F)

```
CLA  INS  P1   P2   Lc   Data...
0x90 0x5F 0x00 0x00 N    [FileNo][FileOption][AccRights][SDMOptions]
                         [SDMAccessRights][ENCPICCDataOff][SDMMACOff]
                         [SDMMACInputOff]
```

Con sesión EV2 activa, TapLinx añade automáticamente el MAC de sesión.

---

## Clave offline (Package Key)

La clave offline proporcionada identifica el paquete ante el servidor de licencias NXP TapLinx:

```
pmHc+9ACbu+JGvPgXl3kCq5pL/dV8Bi6iPQPCMl2YLcW7Rp2+Tku5j63LWzlp0wx...
```

Esta clave se pasa a `NxpNfcLib.registerActivity()` al inicializar la biblioteca.

---

## Referencias

- **AN12196** – NTAG 424 DNA features and hints (SDM/SUN configuration)
- **MF3D_H_X3_SDS** – MIFARE DESFire EV3 Short Data Sheet (SDM en EV3)
- **AN12343** – MIFARE DESFire Light Features and Hints
- **AN10922** – NDEF on MIFARE DESFire
- TapLinx Android SDK 5.0.0

---

## Notas importantes

- El tag debe estar en **estado de fábrica** (clave master PICC por defecto = 16 bytes 0x00)
  para que la escritura inicial funcione sin errores
- Si el tag ya tiene aplicaciones creadas, la app intentará reutilizarlas
- El SDM requiere autenticación **EV2First** (AES-128), no autenticación DES nativa
- Para verificar los datos SDM en el backend, se necesita la clave SDMFileRead del tag
  (por defecto key 0 = 16 bytes 0x00)

---

*Desarrollado con TapLinx 5.0 © NXP Semiconductors*
