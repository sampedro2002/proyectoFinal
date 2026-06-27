# Sistema de Control de Consumo de Alimentos por Huella Digital

Bienvenido al repositorio principal del proyecto **Control Eat Food**, un sistema integral diseñado para registrar, administrar y monitorear el consumo de alimentos del personal mediante identificación biométrica por huella digital (lector ZKTeco ZK9500). 

Este directorio principal consolida los dos componentes fundamentales del sistema:

## 📂 Estructura del Proyecto

*   **[controlEatFoodWeb](./controlEatFoodWeb/)**: El núcleo del sistema. Contiene el **Backend** (construido con Spring Boot 3, gestiona la API REST, la seguridad y el motor biométrico 1:N) y el **Frontend Web** (una PWA construida en React + Vite) usada para el panel de administración, la interfaz de los puntos de catering y el modo kiosco con soporte offline.
*   **[controlEatFoodMovil](./controlEatFoodMovil/)**: La **Aplicación Móvil Android** (desarrollada en Kotlin con Jetpack Compose). Está orientada a la gestión administrativa rápida desde dispositivos móviles, permitiendo consultar dashboards, gestionar empleados y registrar consumos manuales (incluyendo un modo kiosco mediante conexión USB OTG al lector biométrico).

## 🚀 Puesta en Marcha

Cada componente tiene sus propios requisitos y pasos para ser configurado correctamente. Te recomendamos iniciar revisando el componente web, ya que el backend es necesario para que funcione la aplicación móvil.

1.  **Backend y Frontend Web**: 
    Consulta las instrucciones completas en [`controlEatFoodWeb/README.md`](./controlEatFoodWeb/README.md).
    *(Requiere: JDK 21, MySQL 8.0+ y Node.js)*
2.  **App Móvil (Android)**: 
    Consulta las instrucciones de compilación y emulación en [`controlEatFoodMovil/README.md`](./controlEatFoodMovil/README.md).
    *(Requiere: Android Studio, JDK 17+ y el backend en ejecución)*

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
