# Sistema de Control de Consumo de Alimentos por Huella Digital

Bienvenido al repositorio principal del proyecto **Control Eat Food**, un sistema integral diseñado para registrar, administrar y monitorear el consumo de alimentos del personal mediante identificación biométrica por huella digital (lector ZKTeco ZK9500). 

Este directorio principal consolida los dos componentes fundamentales del sistema:

## 📂 Estructura del Proyecto

*   **[controlEatFoodWeb](./controlEatFoodWeb/)**: El núcleo del sistema. Contiene el **Backend** (construido con Spring Boot 3, gestiona la API REST, la seguridad y el motor biométrico 1:N) y el **Frontend Web** (una PWA construida en React + Vite) usada para el panel de administración, la interfaz de los puntos de catering y el modo kiosco con soporte offline.
*   **[controlEatFoodMovil](./controlEatFoodMovil/)**: La **Aplicación Móvil Android** (desarrollada en Kotlin con Jetpack Compose). Está orientada a la gestión administrativa rápida desde dispositivos móviles, permitiendo consultar dashboards, gestionar empleados y registrar consumos manuales (incluyendo un modo kiosco mediante conexión USB OTG al lector biométrico).
*   **[RunWindowns](./RunWindowns/)**: Scripts de automatización para Windows.

## 📦 Scripts de Instalación (RunWindowns)

| Archivo | Propósito |
|---------|-----------|
| `Inicio.bat` | **Punto de entrada único** (doble clic): lanza `Inicio.ps1` sin bloqueos de política de ejecución |
| `Inicio.ps1` | Toda la lógica: menú **Pruebas**, **Instalación nueva**, **Reinstalación** (conserva claves/huellas), actualizar, reparar, desinstalar y diagnósticos |
| `setup.exe` | Instalador del SDK del lector biométrico ZK9500 (se ejecuta una sola vez, desde cualquiera de los dos caminos) |
| `config/` | Configuración generada en modo Producción (install_config.json, application-prod.yml) |
| `logs/` | Logs de instalación y del servicio |

**Pruebas (Modo Desarrollo):** no requiere permisos de Administrador. Verifica/instala Java 21, Node.js y Maven; configura la base de datos (te pregunta si es **local**, detectando/creando Docker `control-mysql` o MySQL local automáticamente, o **remota**, pidiendo host/usuario/password); instala dependencias del frontend; y opcionalmente arranca backend y frontend en ventanas separadas.

**Producción:** requiere Administrador (se auto-eleva con UAC al elegir esta opción). Ya no ofrece Docker: se conecta **siempre directo a un servidor MySQL remoto** (host/usuario/password), con opción de crear la BD/usuario ahí mismo si hace falta. Además:
- Genera claves de seguridad (JWT, AES) y las **persiste protegidas (DPAPI)** en `C:\ProgramData\ControlEatFood\`, reutilizándolas en reinstalaciones para no invalidar las huellas ya cifradas
- Compila y empaqueta para producción
- Registra como servicio de Windows (NSSM)
- Configura firewall
- Permite actualizar, reparar, desinstalar y ver diagnósticos desde el mismo menú

## 🚀 Puesta en Marcha (Paso a Paso)

Para arrancar el proyecto, es **fundamental configurar el entorno antes de hacer cualquier otra cosa**. El repositorio incluye scripts de automatización en las carpetas **`RunWindowns`** (para Windows) y **`RunLinux`** (para Linux).

---

### 🛠️ Paso 1: Configurar el Entorno (Ejecutar primero)

Dependiendo de tu sistema operativo, sigue las instrucciones para preparar Java, Node.js y la base de datos MySQL (local o en Docker).

#### 💻 En Windows
1. Abre una terminal de comandos (CMD o PowerShell) en la raíz de este proyecto.
2. Dirígete a la carpeta `RunWindowns`, ejecuta `Inicio.bat` y elige la opción **[1] Pruebas**:
   ```cmd
   cd RunWindowns
   Inicio.bat
   ```
   *El script verificará e instalará automáticamente Java 21, Node.js y Maven (vía Winget). Para la base de datos te preguntará si te vas a conectar a un servidor **remoto** (te pide host/usuario/password y prueba la conexión) o si es **local** (en ese caso detecta/crea automáticamente el contenedor Docker `control-mysql` o usa un MySQL local instalado). Al final te ofrece arrancar backend y frontend automáticamente en ventanas separadas.*
3. **Instalar el Driver/Agente del Lector Biométrico**:
   - Para que el cliente Web (Frontend) pueda comunicarse con el lector ZK9500 a través de WebSockets, debes ejecutar el instalador del SDK/agente local ubicado en:
      `RunWindowns/setup.exe`
   - Sigue los pasos en pantalla del asistente de instalación.
4. **Importante**: Si el script instaló Java o Node.js, cierra la terminal actual y abre una nueva para que las variables de entorno surtan efecto.

---

### 🛠️ Paso 2: Ejecución de los Componentes

Una vez que el entorno y la base de datos están configurados e iniciados:

1.  **Backend y Frontend Web**: 
    Consulta las instrucciones completas de instalación y arranque en el [README de la Web](./controlEatFoodWeb/README.md) (`controlEatFoodWeb/README.md`).
    *(El backend necesita estar encendido para interactuar con la base de datos y proveer la API REST).*
2.  **App Móvil (Android)**: 
    Consulta las instrucciones para compilar y emular en el [README de la App Móvil](./controlEatFoodMovil/README.md) (`controlEatFoodMovil/README.md`).
    *(Requiere Android Studio y que el backend esté ejecutándose).*

## 🔑 Credenciales Iniciales

Al primer arranque, `DataInitializer` crea/asegura estos usuarios (contraseñas cifradas con BCrypt):

| Rol | Usuario | Contraseña |
|-----|---------|-----------|
| Administrador | `admin` | `Admin123*` |
| Recursos Humanos | `rrhh` | `Rrhh123*` |
| Catering (1 por catering) | `cateringNorte`, `cateringCentro`, `cateringSur` | `restaurant123` |

> **Cámbialas en producción.**

## 🧱 Arquitectura General

El sistema opera bajo una arquitectura cliente-servidor, donde el backend de Spring Boot actúa como el centro de datos y procesamiento de reglas de negocio, y múltiples clientes se conectan a él mediante HTTPS/REST.

- **Integración Biométrica**: 
  - En **Web**: Los kioscos de catering leen la huella digital utilizando un agente local (ZKFinger WebAPI) conectado por WebSocket, el cual envía la plantilla al backend para su identificación 1:N.
    - *UX Mejorada en Enrolamiento*: El frontend web avanza automáticamente al siguiente dedo libre tras registrar una huella y deshabilita los dedos ya usados para evitar sobrescrituras. Es necesario colocar **el mismo dedo 3 veces** durante un registro para garantizar una lectura correcta.
  - En **Móvil**: La aplicación móvil Android puede conectar el lector directamente usando un cable USB OTG y el SDK integrado en la app.
- **Soporte Offline**: Ambos clientes, web y móvil, disponen de una capa offline (usando IndexedDB en web y Room en Android) que almacena los registros localmente en caso de pérdida de conectividad, sincronizándolos automáticamente en cuanto la red vuelve a estar disponible.

## 🔐 Seguridad y Privacidad

- **Cifrado Biométrico**: Las plantillas de huellas digitales de los empleados se cifran usando **AES-256/GCM** (con verificación de integridad) antes de almacenarse en la base de datos. La clave se **persiste protegida con DPAPI** en `C:\ProgramData\ControlEatFood\` para sobrevivir a reinstalaciones (ver [Clave de cifrado y reinstalaciones](#-clave-de-cifrado-y-reinstalaciones-importante)).
- **Autenticación**: Todos los accesos se validan y aseguran con tokens JWT (JSON Web Tokens).
- **Roles y Auditoría**: El acceso está particionado en roles (Administrador, Catering) y todas las acciones críticas (cambios, altas, bajas) quedan registradas en un log de auditoría inmutable.

## 🌍 Puesta en Producción

Para desplegar el sistema en un entorno de producción, sigue estas directrices y mejores prácticas:

### 1. Instalación Automatizada (Recomendado)

El proyecto incluye un **instalador automatizado** para Windows Server que guía paso a paso la configuración del entorno de producción:

```cmd
cd RunWindowns
Inicio.bat
:: Elegir la opción [2] Produccion
:: Se solicitarán permisos de Administrador automáticamente (UAC)
```

**El instalador interactivo permite:**
- ✅ Instalar prerequisitos (Java 21, Node.js, Maven)
- ✅ Configurar base de datos: se conecta directo a un **servidor MySQL remoto** (sin Docker), con opción de crear la BD/usuario ahí mismo
- ✅ Probar conexión TCP y autenticación MySQL
- ✅ Generar automáticamente JWT_SECRET y BIOMETRIC_ENCRYPTION_KEY
- ✅ Compilar backend (JAR) y frontend (dist)
- ✅ Registrar como **servicio de Windows** (con NSSM)
- ✅ Configurar firewall y reverse proxy
- ✅ Actualizar, reparar o desinstalar

El instalador guarda la configuración en `RunWindowns/config/install_config.json` para futuras actualizaciones.

### 🔑 Clave de cifrado y reinstalaciones (IMPORTANTE)

Las huellas se guardan **cifradas** en la base de datos. Si la clave de cifrado cambia, esas huellas quedan **ilegibles** (`HUELLA NO ENCONTRADA` / `AEADBadTagException`). Como la base de datos **sobrevive** a una desinstalación pero antes la clave se regeneraba en cada instalación, reinstalar rompía todas las huellas. Para evitarlo:

- La clave de cifrado (AES) y el `JWT_SECRET` se **persisten de forma protegida (DPAPI) en `C:\ProgramData\ControlEatFood\`**, **fuera** de `RunWindowns/config/` (que sí se borra al desinstalar). Así **sobreviven a reinstalaciones**.
- El instalador **reutiliza** esa clave si ya existe; solo genera una nueva cuando no hay ninguna. Verás en el log `Clave de cifrado biometrico reutilizada`.
- Se guarda un **respaldo legible** en `C:\ProgramData\ControlEatFood\biometric-key-RESPALDO-NO-BORRAR.txt`. **Cópialo a un lugar seguro (fuera del equipo).** Es tu única recuperación si cambias de máquina o borras `ProgramData`.

**Menú del instalador:**

| Opción | Cuándo usarla |
|--------|---------------|
| **[2] Instalación nueva** | Equipo/BD nuevos. Genera claves nuevas si no existen. |
| **[3] Reinstalación (conservar datos y claves)** | Reinstalar sobre una BD con huellas ya registradas. Reutiliza la clave persistida; si no la encuentra (equipo nuevo), **pide pegar** la clave del respaldo. |

**Recuperación en otro equipo:** elige **[3] Reinstalación** y pega el contenido de la clave del archivo de respaldo cuando el instalador lo solicite. Dejar en blanco genera una clave nueva y las huellas viejas quedarán ilegibles (habría que re-enrolarlas).

**Migración de una instalación anterior:** si ya tenías huellas de antes de esta mejora, siembra tu clave actual (la de `RunWindowns/config/install_config.json`, campo `production.biometricEncryptionKey`) en `C:\ProgramData\ControlEatFood\biometric.key` **antes** de reinstalar, o simplemente usa **[3] Reinstalación** y pégala. Una clave equivocada ya **no tumba** el sistema: la huella ilegible se omite (queda registrado en `GET /api/fingerprints/biometric-status` con `indexMatchesDb=false`) y se puede re-enrolar.

### 2. Base de Datos
- Utiliza una instancia dedicada de MySQL 8.
- Asegúrate de cambiar las contraseñas predeterminadas y restringir el acceso remoto.
- Configura copias de seguridad (backups) automáticas y periódicas de la base de datos `control_almuerzos`.

### 3. Backend (Spring Boot)
1. Compila el proyecto para generar el archivo JAR ejecutable:
   ```bash
   cd controlEatFoodWeb/backend
   mvn clean package -DskipTests
   ```
2. Ejecuta el JAR (`target/control-eat-food-0.0.1-SNAPSHOT.jar` o similar) definiendo obligatoriamente las variables de entorno de seguridad:
   ```bash
   export DB_URL=jdbc:mysql://tu-servidor-bd:3306/control_almuerzos
   export DB_USER=tu_usuario
   export DB_PASSWORD=tu_password_seguro
   export JWT_SECRET=tu_clave_jwt_base64_muy_larga_256bits
   export BIOMETRIC_ENCRYPTION_KEY=tu_clave_aes_128_minimo_16_bytes
   export PUBLIC_URL=https://tu-dominio.com
   
   java -jar target/backend-0.0.1-SNAPSHOT.jar
   ```
   *(Nota: Se recomienda administrar el proceso mediante `systemd` o contenedorizarlo con Docker para garantizar su reinicio automático).*

### 4. Frontend Web (React + Vite)
1. Genera los archivos estáticos optimizados para producción:
   ```bash
   cd controlEatFoodWeb/frontend
   npm run build
   ```
2. Esto creará una carpeta `dist/`. Este contenido es estático y debe ser servido utilizando un servidor web moderno (ver Paso 4).

### 5. Servidor Web y Proxy Inverso (Nginx)
Se recomienda utilizar Nginx como servidor web para entregar la SPA de React (Frontend) y redirigir el tráfico hacia la API (Backend), asegurando todas las comunicaciones con HTTPS:

```nginx
server {
    listen 80;
    server_name tu-dominio.com;
    
    # Redirigir HTTP a HTTPS (Configuración SSL omitida por brevedad)

    # Servir Frontend Web
    location / {
        root /ruta/a/tu/proyecto/controlEatFoodWeb/frontend/dist;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # Proxy Inverso hacia el Backend de Spring Boot
    location /api/ {
        proxy_pass http://localhost:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 6. App Móvil Android
1. Ajusta la URL de conexión en `controlEatFoodMovil/local.properties` para que apunte a tu entorno de producción:
   ```properties
   API_BASE_URL=https://tu-dominio.com/api
   ```
2. Genera el APK o App Bundle (AAB) firmado listo para su distribución:
   ```bash
   cd controlEatFoodMovil
   ./gradlew :app:assembleRelease
   ```
