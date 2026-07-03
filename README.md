# Sistema de Control de Consumo de Alimentos por Huella Digital

Bienvenido al repositorio principal del proyecto **Control Eat Food**, un sistema integral diseñado para registrar, administrar y monitorear el consumo de alimentos del personal mediante identificación biométrica por huella digital (lector ZKTeco ZK9500). 

Este directorio principal consolida los dos componentes fundamentales del sistema:

## 📂 Estructura del Proyecto

*   **[controlEatFoodWeb](./controlEatFoodWeb/)**: El núcleo del sistema. Contiene el **Backend** (construido con Spring Boot 3, gestiona la API REST, la seguridad y el motor biométrico 1:N) y el **Frontend Web** (una PWA construida en React + Vite) usada para el panel de administración, la interfaz de los puntos de catering y el modo kiosco con soporte offline.
*   **[controlEatFoodMovil](./controlEatFoodMovil/)**: La **Aplicación Móvil Android** (desarrollada en Kotlin con Jetpack Compose). Está orientada a la gestión administrativa rápida desde dispositivos móviles, permitiendo consultar dashboards, gestionar empleados y registrar consumos manuales (incluyendo un modo kiosco mediante conexión USB OTG al lector biométrico).

## 🚀 Puesta en Marcha (Paso a Paso)

Para arrancar el proyecto, es **fundamental configurar el entorno antes de hacer cualquier otra cosa**. El repositorio incluye scripts de automatización en las carpetas **`RunWindowns`** (para Windows) y **`RunLinux`** (para Linux).

---

### 🛠️ Paso 1: Configurar el Entorno (Ejecutar primero)

Dependiendo de tu sistema operativo, sigue las instrucciones para preparar Java, Node.js y la base de datos MySQL (local o en Docker).

#### 💻 En Windows
1. Abre una terminal de comandos (CMD o PowerShell) en la raíz de este proyecto.
2. Dirígete a la carpeta `RunWindowns` y ejecuta el script de configuración:
   ```cmd
   cd RunWindowns
   setup_env.bat
   ```
   *El script verificará e instalará automáticamente Java 21 y Node.js (vía Winget), e intentará configurar el contenedor de base de datos MySQL (`control-mysql` en Docker) o un servidor MySQL local.*
3. **Instalar el Driver/Agente del Lector Biométrico**:
   - Para que el cliente Web (Frontend) pueda comunicarse con el lector ZK9500 a través de WebSockets, debes ejecutar el instalador del SDK/agente local ubicado en:
     `RunWindowns/setup.exe`
   - Sigue los pasos en pantalla del asistente de instalación.
4. **Importante**: Si el script instaló Java o Node.js, cierra la terminal actual y abre una nueva para que las variables de entorno surtan efecto.

#### 🐧 En Linux
1. Abre tu terminal en la raíz del proyecto.
2. Dirígete a la carpeta `RunLinux`, dale permisos de ejecución al script y ejecútalo:
   ```bash
   cd RunLinux
   chmod +x setup_env.sh
   ./setup_env.sh
   ```
   *El script verificará tu gestor de paquetes (APT, DNF, Pacman, etc.) e instalará OpenJDK 21, Node.js y configurará la base de datos MySQL.*
3. **Bibliotecas Nativas del SDK**:
   - En Linux, el backend de Spring Boot interactúa con el SDK de biometría a través de las librerías nativas `.so` contenidas en `RunLinux/lib-x64/` (o `lib-x86/` para 32 bits).
   - Estas librerías son requeridas si decides usar biometría real (`BIOMETRIC_PROVIDER=zk`).
4. **Importante**: Si el script instaló Java o Node.js, ejecuta `source ~/.bashrc` o reinicia la terminal.

---

### 🛠️ Paso 2: Ejecución de los Componentes

Una vez que el entorno y la base de datos están configurados e iniciados:

1.  **Backend y Frontend Web**: 
    Consulta las instrucciones completas de instalación y arranque en el [README de la Web](./controlEatFoodWeb/README.md) (`controlEatFoodWeb/README.md`).
    *(El backend necesita estar encendido para interactuar con la base de datos y proveer la API REST).*
2.  **App Móvil (Android)**: 
    Consulta las instrucciones para compilar y emular en el [README de la App Móvil](./controlEatFoodMovil/README.md) (`controlEatFoodMovil/README.md`).
    *(Requiere Android Studio y que el backend esté ejecutándose).*

## 🧱 Arquitectura General

El sistema opera bajo una arquitectura cliente-servidor, donde el backend de Spring Boot actúa como el centro de datos y procesamiento de reglas de negocio, y múltiples clientes se conectan a él mediante HTTPS/REST.

- **Integración Biométrica**: 
  - En **Web**: Los kioscos de catering leen la huella digital utilizando un agente local (ZKFinger WebAPI) conectado por WebSocket, el cual envía la plantilla al backend para su identificación 1:N.
  - En **Móvil**: La aplicación móvil Android puede conectar el lector directamente usando un cable USB OTG y el SDK integrado en la app.
- **Soporte Offline**: Ambos clientes, web y móvil, disponen de una capa offline (usando IndexedDB en web y Room en Android) que almacena los registros localmente en caso de pérdida de conectividad, sincronizándolos automáticamente en cuanto la red vuelve a estar disponible.

## 🔐 Seguridad y Privacidad

- **Cifrado Biométrico**: Las plantillas de huellas digitales de los empleados se cifran usando AES-128/CBC antes de almacenarse en la base de datos.
- **Autenticación**: Todos los accesos se validan y aseguran con tokens JWT (JSON Web Tokens).
- **Roles y Auditoría**: El acceso está particionado en roles (Administrador, Supervisor, Catering) y todas las acciones críticas (cambios, altas, bajas) quedan registradas en un log de auditoría inmutable.
