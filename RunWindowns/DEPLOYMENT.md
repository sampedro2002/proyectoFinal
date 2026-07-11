# Notas de despliegue en producción

Este documento cubre riesgos y decisiones de seguridad del despliegue en Windows
(`Inicio.ps1` → modo Producción) que no se resuelven solos con la instalación
automática, y que quien administre el servidor debe conocer.

## Secretos del servicio de Windows quedan en texto plano

El servicio `ControlEatFood` se registra con NSSM (`Step-ConfigureService` en
`Inicio.ps1`), que guarda `DB_PASSWORD`, `JWT_SECRET` y `BIOMETRIC_ENCRYPTION_KEY`
como variables de entorno del servicio (`AppEnvironmentExtra`). Windows almacena
esas variables **en texto plano** en el registro
(`HKLM\SYSTEM\CurrentControlSet\Services\ControlEatFood\Parameters`), y cualquier
administrador local de esa máquina puede leerlas con:

- `nssm dump ControlEatFood`
- `sc.exe qc ControlEatFood` + inspección del registro
- El Administrador de tareas / Process Explorer, inspeccionando el entorno del proceso

Esto es una limitación de NSSM/Windows, no un bug de la aplicación. Implementar
DPAPI o un gestor de credenciales externo (Credential Manager, Azure Key Vault,
etc.) para evitarlo es un esfuerzo desproporcionado para el tamaño actual del
proyecto — **la mitigación es organizativa, no técnica**:

- El acceso de Administrador al servidor donde corre el servicio debe estar
  limitado estrictamente a las personas que necesiten operarlo.
- No compartir la cuenta de Administrador local; usar cuentas nominales.
- Si en algún momento se sospecha que un secreto quedó expuesto (por ejemplo, se
  compartió el log o una captura de pantalla del `nssm dump`), rotarlo: correr de
  nuevo `Inicio.ps1` → Producción → Reparar → "Reconectar base de datos" (para la
  contraseña de BD) o reinstalar con un nuevo `JWT_SECRET`/`BIOMETRIC_ENCRYPTION_KEY`
  generado automáticamente.

## Cifrado de plantillas biométricas (AES-256-GCM)

Desde esta versión, `AesFingerprintConverter` usa AES-256/GCM en vez de
AES-128/CBC. GCM valida la integridad del dato al descifrar (rechaza ciphertext
manipulado), y la clave configurada (`BIOMETRIC_ENCRYPTION_KEY`) se normaliza
siempre a 32 bytes vía SHA-256, sin importar su longitud original.

**Importante**: este formato no es compatible con el anterior (CBC). Como la base
de datos de producción está vacía, no aplica ninguna migración — pero si alguna
vez se restaura un backup con huellas cifradas con el esquema viejo, hay que
re-enrolarlas (no se pueden convertir automáticamente).

El instalador (`Step-ConfigureProduction`) ya no permite dejar la clave vacía: la
aplicación rechaza arrancar en el perfil `prod` sin ella (`StartupSecurityValidator`).

## Conexión a MySQL

`Inicio.ps1` pregunta, solo en modo Producción, si el servidor MySQL soporta
SSL/TLS. Si se responde que sí, la URL JDBC usa `sslMode=REQUIRED` (cifra la
conexión pero no valida el certificado contra una CA — apropiado para un MySQL
con certificado autofirmado). Si el servidor tiene una CA propia y se quiere
validación estricta del certificado (`VERIFY_CA`/`VERIFY_IDENTITY`), hay que
ajustarlo manualmente en `application-prod.yml` tras la instalación.

## TLS del backend (HTTP → HTTPS) — reverse proxy Caddy

El backend de Spring Boot sigue corriendo en HTTP plano en `localhost:<BackendPort>`
(por defecto 3000) — **nunca se expone directo al firewall**. Delante de él,
`Inicio.ps1` puede instalar y registrar [Caddy](https://caddyserver.com/) como un
segundo servicio de Windows (`ControlEatFoodProxy`) que:

- Escucha en `:443` (HTTPS) y `:80` (solo para redirigir a `:443`).
- Reenvía todo el tráfico a `localhost:<BackendPort>` (`reverse_proxy`), incluido
  el WebSocket del biométrico (`/zkfinger-ws`) — Caddy detecta el `Connection:
  Upgrade` automáticamente, no requiere configuración especial.
- Genera y usa su **CA interna** (`tls internal`), no Let's Encrypt: este
  despliegue es de red interna (LAN, sin dominio público), así que no hay forma
  de obtener un certificado público válido. El firewall solo abre 443/80; el
  puerto del backend quedó sin regla (solo alcanzable desde `localhost`, o sea
  desde el propio Caddy).

### Confiar en el certificado desde cada equipo cliente

Como la CA de Caddy no es una autoridad pública, cada navegador que se conecte
por primera vez a `https://<IP-del-servidor>` va a mostrar una advertencia de
certificado no confiable — **hasta que ese equipo confíe en la CA interna**.

Al finalizar la instalación, el certificado raíz queda exportado en:

```
RunWindowns\config\caddy-root-ca.crt
```

El propio instalador ya lo instala automáticamente en el almacén de confianza
**del servidor** (vía `certutil -addstore -f Root ...`, se ejecuta elevado).
Para cada PC/kiosco adicional que use la app, copiar ese `.crt` y ejecutar ahí:

```
certutil -addstore -f Root "caddy-root-ca.crt"
```

o instalarlo por GUI: doble clic en el archivo → **Instalar certificado** →
**Equipo local** → **Entidades de certificación raíz de confianza**.

Este certificado **no es secreto** (es la mitad pública de la CA) — se puede
distribuir libremente por USB, red compartida, etc. Lo que sí hay que resguardar
es la clave privada de la CA, que Caddy guarda cifrada en
`RunWindowns\tools\caddy\data\pki\authorities\local\` (ya cubierto por
`RunWindowns/tools/` en `.gitignore`).

### App móvil (Android)

`controlEatFoodMovil` tiene la URL del servidor configurable por el usuario (no
viene hardcodeada), y permite tráfico HTTP sin cifrar (`network_security_config.xml`,
pensado para uso en LAN). Al mover el backend detrás de Caddy:

- Actualizar la URL configurada en la app (Ajustes / aprovisionamiento por QR) a
  `https://<IP-del-servidor>`.
- El dispositivo Android también necesita confiar en el certificado de la CA
  interna de Caddy (`caddy-root-ca.crt`) para que la conexión HTTPS no falle por
  certificado no confiable — instalarlo vía Ajustes → Seguridad → Instalar
  certificado, o distribuirlo por MDM si se administran los equipos así.

### Si el servidor ya tiene IIS u otro proxy

`Inicio.ps1` pregunta explícitamente si se quiere configurar Caddy — si el
servidor ya tiene IIS con el módulo ARR (o cualquier otro reverse proxy)
apuntando al backend, responder que no y configurar el TLS ahí en su lugar. En
ese caso, `Step-ConfigureFirewall` abre directamente el puerto del backend, tal
como antes de este cambio.
