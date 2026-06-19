# Librerías nativas del SDK ZKFinger (ZK9500)

Librerías nativas del **ZKFinger SDK** de ZKTeco para habilitar el matching
biométrico 1:N en el servidor.

## Estructura

```
native/
├── linux-x64/     # libzkfp.so + dependencias para Linux 64-bit
├── linux-x86/     # libzkfp.so + dependencias para Linux 32-bit
├── win-x64/       # libzkfp.dll + dependencias para Windows 64-bit
└── win-x86/       # libzkfp.dll + dependencias para Windows 32-bit
```

Las librerías de las cuatro plataformas ya están copiadas desde el SDK oficial
(`ZKFingerSDK_Windows_Standard` y `ZKFingerSDK_Linux`). Los `.dll` sueltos en la
raíz de `native/` son copias heredadas; el backend ya **no** depende de ellas.

## Selección automática de plataforma

El backend resuelve la carpeta correcta en tiempo de ejecución
(`NativeLibLoader.resolvePlatformPath`) según `os.name`/`os.arch`:

| SO / arquitectura | Carpeta usada |
|-------------------|---------------|
| Windows 64-bit    | `win-x64`     |
| Windows 32-bit    | `win-x86`     |
| Linux 64-bit      | `linux-x64`   |
| Linux 32-bit      | `linux-x86`   |

- **Windows**: JNA carga `libzkfp.dll` por ruta absoluta y el cargador del sistema
  resuelve las DLL dependientes en la misma carpeta.
- **Linux**: el enlazador dinámico no busca dependencias junto a la librería, así que
  el backend **precarga** los `.so` de la carpeta (`libcrypto`, `libsqlite3`,
  `libusb`, `libiomp5`, `libidkit`, `libzkfinger10`, …) antes de cargar `libzkfp.so`.
  No hace falta exportar `LD_LIBRARY_PATH`.

## Configuración

La ruta **base** se configura con `app.biometric.native-lib-path` (o la variable de
entorno `ZK_NATIVE_PATH`), por defecto `./native`. **No** apuntes a la subcarpeta de
plataforma: el backend le añade `win-x64`/`linux-x64`/etc. automáticamente.

> Estas librerías son propietarias de ZKTeco.

Si las librerías no están presentes, el backend arranca igual pero el motor
biométrico queda deshabilitado. Para desarrollo sin hardware usa `app.biometric.provider=sim`.
