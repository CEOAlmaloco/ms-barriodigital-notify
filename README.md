# ms-barriodigital-notify

Consumidor de **RabbitMQ** de BarrioDigital.

Puerto: **8083**

## Responsabilidad

Lee los eventos que publica `ms-barriodigital-requests` al crear o cambiar un trámite y ejecuta el efecto de cada uno:

| Cola | Qué hace |
|---|---|
| `q.cmd.email` | Email de admisión, visita y cierre |
| `q.cmd.crew` | Ticket para la cuadrilla |
| `q.cmd.certificate` | Comprobante y certificado en PDF |

No es público. No pasa por el API Gateway ni por el BFF: solo habla con el broker. El puerto 8083 queda para el health.

Los contratos están en `barriodigital-infra/docs/decisiones.md`: mensajería (EP2-01), topología (EP2-02) y ACK, reintentos y DLQ (EP2-03).

## Estado (EP2-17)

Conecta al broker y, al abrir la conexión, declara la topología del contrato EP2-02: 3 exchanges, 6 colas y 9 bindings. EP2-16 incorpora el listener de email y EP2-17 el de cuadrillas; ambos reutilizan sin cambios el ACK manual, los reintentos y la idempotencia de EP2-15. Certificados (EP2-18) sigue pendiente. La publicación desde requests corresponde a otras tareas; los consumidores pueden validarse con mensajes sintéticos.

El listener inicia la conexión al arrancar. Recibe el `Message` y el `Channel` y los entrega al procesador compartido antes de validar el tipo y el payload. No hay conversión automática de DTO previa al procesador.

El health de RabbitMQ está activo. `/actuator/health` en `UP` significa que notify se conectó al broker, y la topología se declara en esa misma conexión. Sin broker responde `DOWN`. El indicador de correo está desactivado: el health no abre una conexión SMTP y no acredita entrega de correos.

## Variables

Se leen del entorno o de un `.env` en la raíz del módulo (plantilla en `.env.example`). Ese archivo se carga explícitamente mediante `spring.config.import: optional:file:.env[.properties]`; no depende de una carga automática de dotenv.

| Variable | Por defecto | Qué es |
|---|---|---|
| `RABBIT_ADDRESSES` | `localhost:5672` | Nodos separados por coma |
| `RABBIT_USER` | `barriodigital` | Usuario del broker |
| `RABBIT_PASSWORD` | ninguno | Password del broker. Sin ella la app arranca, pero no se conecta y el health queda `DOWN` |

En local solo el nodo 1 del clúster publica el `5672` en el host. El nodo 2 no abre puertos, por eso el valor por defecto lleva solo `localhost`. Dentro de la red del compose del clúster se pueden poner los dos: `rabbitmq-1:5672,rabbitmq-2:5672`.

El timeout de conexión es de 2000 ms (`spring.rabbitmq.connection-timeout`).

## Correos: payload y contenido

El listener usa `barriodigital.rabbit.queue.email`. Acepta exclusivamente los tres tipos de la tabla:

| type | Asunto exacto | Mensaje principal exacto |
|---|---|---|
| `email.admitted` | BarrioDigital: trámite admitido | Tu trámite fue admitido para su gestión. |
| `email.visit` | BarrioDigital: trámite en terreno | Tu trámite pasó a la etapa de atención en terreno. |
| `email.resolved` | BarrioDigital: trámite resuelto | Tu trámite fue marcado como resuelto. |

El payload es un objeto con estos campos:

- `solicitanteEmail`: obligatorio, textual y no vacío tras recortar espacios. Debe ser una dirección única válida según Jakarta Mail, sin consulta DNS. No se admiten listas, grupos, nombres visibles ni saltos de línea. Se conserva la capitalización.
- `requestId`, `procedureType`, `procedureLabel`, `address`: opcionales. Admiten ausencia, null y texto vacío. Un valor no nulo de otro tipo JSON es un error no recuperable. Los textos se recortan para presentarlos, sin modificar el payload.

Una única composición produce el texto plano para ambos senders. Tras el mensaje principal aparece una línea en blanco y:

```text
Trámite: <procedureLabel, si tiene texto; si no, procedureType; si no, Trámite vecinal>
Identificador: <requestId, solo cuando tiene texto>
Dirección: <address, solo cuando tiene texto>
```

No consulta otros servicios ni copia etiquetas del catálogo. No personaliza el saludo, inventa fechas de visita ni añade HTML o adjuntos. SMTP codifica asunto y cuerpo en UTF-8.

El parser común exige `type` y `eventId` textuales no vacíos y `payload` objeto. Permite omitir `timestamp`, `traceId` y `correlationId`; no comprueba que los identificadores sean UUID. Si `timestamp` contiene texto, debe poder convertirse a `Instant`. EP2-16 conserva estas reglas de EP2-15.

## Selección de EmailSender y SMTP

`barriodigital.email.mode` selecciona un único sender. El valor predeterminado es `logging`; `smtp` requiere activación explícita. Un valor desconocido impide iniciar por error de binding de la propiedad.

Logging no necesita host, remitente ni credenciales. Registra, por ejemplo, `email-simulado type=email.visit entrega-real=false`: es una simulación sin entrega real. No registra destinatario, dirección, cuerpo ni sobre completo. Al terminar el log, el procesador confirma el mensaje.

SMTP usa `JavaMailSender`, con un envío por invocación. Sus propiedades estándar pueden sobrescribirse con la configuración de Spring; las variables siguientes se enlazan explícitamente desde `application.yml`:

| Variable | Propiedad | Predeterminado / regla |
|---|---|---|
| `EMAIL_MODE` | `barriodigital.email.mode` | `logging` |
| `EMAIL_FROM` | `barriodigital.email.from` | Sin remitente; dirección única obligatoria en SMTP |
| `SMTP_HOST` | `spring.mail.host` | Vacío; obligatorio en SMTP |
| `SMTP_PORT` | `spring.mail.port` | 25; entre 1 y 65535 |
| `SMTP_USER` | `spring.mail.username` | Sin usuario |
| `SMTP_PASSWORD` | `spring.mail.password` | Sin contraseña |
| `SMTP_AUTH` | `spring.mail.properties[mail.smtp.auth]` | false; si es true, exige usuario y contraseña |
| `SMTP_STARTTLS_ENABLE` | `spring.mail.properties[mail.smtp.starttls.enable]` | false |
| `SMTP_STARTTLS_REQUIRED` | `spring.mail.properties[mail.smtp.starttls.required]` | false |
| `SMTP_CONNECTION_TIMEOUT` | `spring.mail.properties[mail.smtp.connectiontimeout]` | 5000 ms |
| `SMTP_READ_TIMEOUT` | `spring.mail.properties[mail.smtp.timeout]` | 5000 ms |
| `SMTP_WRITE_TIMEOUT` | `spring.mail.properties[mail.smtp.writetimeout]` | 5000 ms |

Los tres timeouts deben ser enteros positivos y finitos. Se usa el protocolo `smtp`, con STARTTLS configurable, sin proveedor predeterminado. No se exigen credenciales si la autenticación está desactivada. `spring.mail.test-connection` y `management.health.mail.enabled` quedan en false: no hay conexión SMTP de prueba al iniciar ni al consultar health.

Los secretos se suministran por entorno o `.env` local. `.gitignore` y `.dockerignore` ya excluyen ese archivo. `.env.example` solo contiene la plantilla.

### Errores y límites de entrega

- Tipo desconocido, destinatario inválido o campo opcional de tipo incorrecto: `NonRecoverableException`, sin envío, rechazo sin requeue y log `intentos=0`.
- Autenticación rechazada, dirección inválida o respuesta SMTP permanente identificable (5xx): `NonRecoverableException`.
- Conexión rechazada, timeout, error de transporte con causa de E/S o respuesta SMTP temporal (4xx): `RecoverableException`.
- Se recorren causas, excepciones encadenadas de Jakarta Mail y errores individuales envueltos en `MailSendException`. Una lista de direcciones inválidas de `SendFailedException` también identifica un fallo permanente, salvo que haya una causa temporal explícita. Se conserva la causa original; el motivo clasificado no incorpora destinatarios ni respuestas del servidor. Si hay una causa permanente explícita identificada, prevalece sobre las temporales.
- Una excepción inesperada no clasificable conserva la política de EP2-15: se reintenta. No se transforma todo `MailSendException` en error permanente ni se retorna éxito tras un fallo.

Solo `ManualAckProcessor` reintenta: tres intentos totales con un segundo entre ellos. No hay retry adicional en el sender ni en Spring. El ACK ocurre después de que el sender termine; agotados los intentos se hace NACK sin requeue. RabbitMQ enruta el rechazo/NACK hacia `q.cmd.email.dlq`.

SMTP aceptado no garantiza llegada a la bandeja del destinatario. Un fallo de transporte puede dejar incierto si el servidor aceptó el mensaje. Los reintentos y la deduplicación en memoria no garantizan exactamente un envío. La marca se guarda después del handler y antes de intentar el ACK; un fallo del ACK queda registrado. EP2-16 no incorpora recuperación durable ni otra política de idempotencia.

## Cuadrillas: ticket en log (EP2-17)

El listener de `consumer.crew` usa `barriodigital.rabbit.queue.crew` y acepta exclusivamente el `type` exacto `crew.ticket`. Según EP2-01, se origina al confirmar `EN_GESTION` → `EN_TERRENO`, junto con `email.visit`, con distinto `eventId`. La publicación usa `cmd.direct` y routing key `crew.ticket`; el binding alternativo `cmd.topic` / `crew.#` no amplía los tipos admitidos.

`CrewConsumer` recibe `Message` y `Channel` y delega inmediatamente a `ManualAckProcessor`. El handler es `CrewTicketService`, que valida el payload, compone el record `CrewTicket` y registra el ticket. No convierte el body automáticamente a DTO antes del procesador ni consulta otros servicios o catálogos.

| Campo del payload | Regla |
|---|---|
| `requestId`, `procedureType`, `address` | Obligatorios, textuales y con texto después de recortar espacios. Ausencia, null, vacío, solo blancos o cualquier tipo JSON no textual son errores no recuperables. No se convierten valores a texto ni se inventan datos. `correlationId` no sustituye `requestId`. |
| `procedureLabel` | Opcional. Ausencia, null, vacío o blancos usan `procedureType` ya validado. Un valor no nulo debe ser textual y se recorta para presentarlo. |
| `solicitanteEmail` | No se usa, valida ni registra. Su ausencia, null o contenido no condicionan el ticket. |

La validación no modifica el `JsonNode` original. Sus errores identifican el campo y el problema sin volcar el payload. Se conservan las reglas del parser compartido: no se exige UUID.

El número es `CREW-<eventId completo recibido del parser>`, sin recortar, truncar ni cambiar la capitalización del identificador. No genera UUID, contadores, fechas ni secuencias. El mismo evento conserva el número durante reintentos y reprocesamientos; eventos distintos generan números distintos aunque correspondan al mismo trámite. La unicidad depende del `eventId` suministrado por el productor.

Cada procesamiento correcto registra un único INFO con prefijo `crew-ticket ` y un objeto JSON compacto, usando el `ObjectMapper` configurado por Spring. Ejemplo con datos ficticios:

```text
crew-ticket {"ticketNumber":"CREW-demo-crew-1","eventId":"demo-crew-1","requestId":"caso-demo-1","procedureType":"bache","procedureLabel":"Bache en calle","address":"Los Aromos 120"}
```

El JSON contiene exclusivamente esos seis campos. Conserva tildes y escapa comillas, barras inversas y caracteres de control; los saltos de línea de los datos quedan escapados dentro del JSON. No incluye correo ni sobre completo. Se mantiene el prefijo habitual de fecha, nivel y clase del logger y no cambia el formato global de logging ni el pretty printing del mapper para otros usos.

El handler termina después de llamar al logger. La salida es exclusivamente el sistema de logging existente: no incorpora persistencia de tickets, archivos, PDF, API externa ni asignación efectiva a una cuadrilla. La llamada al logger no garantiza persistencia durable del log. Un fallo de composición o serialización se propaga con su causa, sin registrar un ticket parcial ni retornar éxito.

Solo `ManualAckProcessor` decide ACK, rechazo y reintentos. Después del handler guarda la marca y hace ACK; un duplicado recibe ACK sin repetir el ticket. Tipo desconocido o validación inválida producen `NonRecoverableException`: rechazo sin requeue, sin reintentos, log `mensaje-muerto` con `intentos=0` y destino `q.cmd.crew.dlq`. Los fallos recuperables o inesperados siguen los tres intentos totales y NACK sin requeue al agotarlos. No hay retry adicional ni errores de red o disco específicos del servicio de tickets.

La deduplicación por `cola|eventId` recuerda hasta 10 000 claves en memoria. La expulsión de claves, los reinicios y las múltiples instancias permiten repetir el log conservando el número del ticket. La comprobación y la marca no garantizan exclusión de duplicados concurrentes. Tampoco se deduplican eventos distintos por `requestId`: no es una garantía de exactamente una ejecución.

## Topología

Los nombres viven en `barriodigital.rabbit` de `application.yml`, con las mismas claves que usan requests y mq-admin. Si falta una clave, la app no arranca. Ninguna clase de negocio escribe un nombre: los beans de `RabbitTopologyConfig` los leen de `RabbitNamesProperties`.

| Clave | Valor |
|---|---|
| `exchange.topic` | `cmd.topic` |
| `exchange.direct` | `cmd.direct` |
| `exchange.dead` | `cmd.dead.dlx` |
| `queue.email` | `q.cmd.email` |
| `queue.email-dlq` | `q.cmd.email.dlq` |
| `queue.crew` | `q.cmd.crew` |
| `queue.crew-dlq` | `q.cmd.crew.dlq` |
| `queue.certificate` | `q.cmd.certificate` |
| `queue.certificate-dlq` | `q.cmd.certificate.dlq` |
| `routing.email-send` | `email.send` |
| `routing.email-topic` | `email.*` |
| `routing.crew-ticket` | `crew.ticket` |
| `routing.crew-topic` | `crew.#` |
| `routing.certificate-gen` | `certificate.gen` |
| `routing.certificate-topic` | `certificate.*` |
| `routing.email-dead` | `email.dead` |
| `routing.crew-dead` | `crew.dead` |
| `routing.certificate-dead` | `certificate.dead` |

Exchanges y colas son durables y sin auto-delete. Las tres colas principales son clásicas y llevan `x-dead-letter-exchange=cmd.dead.dlx` y `x-dead-letter-routing-key` con su `*.dead`. Las DLQ no llevan argumentos. notify declara también los exchanges para quedar healthy sin esperar a requests; declarar dos veces lo mismo no cambia nada.

## ACK, reintentos y DLQ

Sigue el contrato EP2-03 de `barriodigital-infra/docs/decisiones.md` ("Contrato: ACK, reintentos y DLQ").

`application.yml` deja el listener en `acknowledge-mode: manual`, `prefetch: 10` y `default-requeue-rejected: false`. Ningún mensaje vuelve solo a la cola. Cada consumidor (EP2-16 a EP2-18) recibe el `Message` y el `Channel` y se los pasa a `ManualAckProcessor` junto con el nombre de su cola, leído de `RabbitNamesProperties`, y su handler. Ahí se decide la respuesta al broker:

| Situación | Acción |
|---|---|
| El handler terminó bien | `basicAck` |
| La cola ya confirmó ese `eventId` | `basicAck` sin llamar al handler, log INFO `duplicado` |
| El cuerpo no es JSON, no es un objeto o le falta `type`, `eventId` o `payload` | `basicReject(requeue=false)`, sin llamar al handler |
| El handler lanza `NonRecoverableException` | `basicReject(requeue=false)`, sin reintentos |
| El handler lanza `RecoverableException` u otra excepción | espera 1 s y reintenta en el mismo hilo; al tercer fallo, `basicNack(requeue=false)` |

Son 3 intentos en total con 1 segundo fijo entre uno y otro (`AckPolicy`). El reintento no devuelve el mensaje al broker. Si la espera se interrumpe, el mensaje se da por agotado y va a la DLQ. `basicReject` y `basicNack` sin requeue hacen que RabbitMQ lo mande por `cmd.dead.dlx` a la `.dlq` de la cola que lo recibió. Las DLQ no tienen consumidor.

### Idempotencia

`ProcessedEvents` recuerda la clave `cola|eventId` de cada mensaje confirmado, hasta 10 000 claves; al pasarse expulsa la más antigua. La clave se guarda solo cuando el handler terminó bien: un mensaje que fue a la DLQ se procesa de nuevo si vuelve a llegar. El mismo `eventId` en dos colas son dos mensajes distintos.

Límite conocido: vive en memoria. Si notify se reinicia la olvida y puede repetir un correo o un PDF. Tampoco se comparte entre instancias.

### Logs

Duplicado, en INFO:

```text
duplicado cola=q.cmd.email eventId=11aa22bb-33cc-44dd-55ee-66ff77889900
```

Mensaje que va a la DLQ, en ERROR:

```text
mensaje-muerto cola=q.cmd.email eventId=aabbccdd-eeff-4011-2233-445566778899 motivo="SMTP no responde" intentos=3
```

- Si el cuerpo no trae `eventId` o no es JSON, `eventId` vale `desconocido`.
- `intentos` vale 0 en un rechazo directo (el sobre no se pudo leer o el handler lanzó `NonRecoverableException`) y 3 si se agotaron los reintentos. Si la espera se interrumpe, vale los intentos que alcanzaron a ejecutarse.
- Si la excepción no es `RecoverableException` ni `NonRecoverableException`, el motivo empieza con el nombre de su clase, por ejemplo `motivo="IllegalStateException: plantilla rota"`.

Si `basicAck`, `basicNack` o `basicReject` fallan (por ejemplo, el canal se cerró), se registra en ERROR `fallo basicAck cola=... eventId=...` y no se relanza.

## Paquetes

Base `cl.duoc.barriodigital.notify`.

| Paquete | Qué va |
|---|---|
| `consumer.email` | Consumidor de `q.cmd.email` |
| `consumer.crew` | Consumidor de `q.cmd.crew` |
| `consumer.certificate` | Consumidor de `q.cmd.certificate` |
| `config` | Conexión al broker y declaración de exchanges, colas y bindings |
| `messaging` | Sobre del mensaje, ACK manual, reintentos e idempotencia |

Cada paquete tiene un `package-info.java` con su responsabilidad, porque Git no versiona carpetas vacías.

## Compilar y probar

```powershell
.\mvnw.cmd -B clean verify
```

Los tests no necesitan broker ni SMTP. Revisan topología, propiedades, política compartida, composición de los tres correos, validación, orden envío/ACK, duplicados y clasificación de fallos SMTP envueltos. Usan `Channel` y `JavaMailSender` simulados; el mensaje MIME sí se construye y serializa para comprobar UTF-8. `ApplicationContextRunner` comprueba selección y errores de configuración sin enviar. `src/test/resources/application.properties` apaga los health de RabbitMQ y correo, desactiva `spring.rabbitmq.listener.simple.auto-startup` y pone una password de prueba. Los listeners siguen activos en la configuración normal.

EP2-17 añade validación, composición y serialización del ticket, preservación del payload, número estable y escape de caracteres. La composición del listener usa `ManualAckProcessor` real, `Channel` simulado y captura del log para comprobar registro antes del ACK y duplicados. Un auxiliar exclusivo de tests permite inyectar un `Sleeper` simulado sin ampliar la API de producción. Los fallos controlados del servicio verifican tres intentos y NACK sin esperas reales; no representan un fallo real del destino de logs.

Se requiere Java 21. Si el wrapper falla en PowerShell con `No se puede indizar en una matriz nula` al consultar `Target[0]`, puede invocarse directamente la distribución Maven 3.9.6 ya instalada por el wrapper, sin modificarlo:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$notifyMaven = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.6" -Recurse -Filter mvn.cmd | Select-Object -First 1
& $notifyMaven.FullName -B clean verify
```

## Validación manual de correos con broker local aislado

Validación manual completada en local. Se conserva el procedimiento para repetirla; no forma parte de los tests automatizados. Usa un contenedor dedicado sin volúmenes compartidos, con puertos distintos a los del clúster del proyecto. Las credenciales siguientes son ficticias y exclusivas de esta demo local. No apuntar estos pasos a un broker compartido.

En una primera consola:

```powershell
docker run --rm -d --name bd-ep2-email-demo `
  -p 127.0.0.1:5679:5672 -p 127.0.0.1:15679:15672 `
  -e RABBITMQ_DEFAULT_USER=ep2-demo -e RABBITMQ_DEFAULT_PASS=solo-demo-local `
  rabbitmq:3.12-management-alpine
docker exec bd-ep2-email-demo rabbitmq-diagnostics -q ping
```

Esperar a que el diagnóstico responda correctamente. Desde la raíz de Notify, iniciar con Java 21 en esa consola y dejarla abierta:

```powershell
$env:RABBIT_ADDRESSES = 'localhost:5679'
$env:RABBIT_USER = 'ep2-demo'
$env:RABBIT_PASSWORD = 'solo-demo-local'
$env:EMAIL_MODE = 'logging'
.\mvnw.cmd spring-boot:run
```

Si aplica, usar la distribución del wrapper descrita antes con el objetivo `spring-boot:run`. En otra consola PowerShell, con Notify healthy:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
$demoApi = 'http://127.0.0.1:15679/api'
$demoAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('ep2-demo:solo-demo-local'))
$demoHeaders = @{Authorization = "Basic $demoAuth"}

function Publish-DemoEmail($eventId, $type, $payload) {
    $envelope = @{type=$type; eventId=$eventId; payload=$payload} | ConvertTo-Json -Depth 5 -Compress
    $publication = @{
        properties=@{delivery_mode=2; content_type='application/json'}
        routing_key=$type
        payload=$envelope
        payload_encoding='string'
    } | ConvertTo-Json -Depth 5 -Compress
    Invoke-RestMethod -Method Post -Uri "$demoApi/exchanges/%2F/cmd.topic/publish" `
      -Headers $demoHeaders -ContentType 'application/json; charset=utf-8' `
      -Body ([Text.Encoding]::UTF8.GetBytes($publication))
}

$demoPayload = @{solicitanteEmail='vecino@example.com'; procedureLabel='Atención vecinal'}
Publish-DemoEmail 'demo-admitted-1' 'email.admitted' $demoPayload
Publish-DemoEmail 'demo-visit-1' 'email.visit' $demoPayload
Publish-DemoEmail 'demo-resolved-1' 'email.resolved' $demoPayload
# Publicar este duplicado cuando ya se haya registrado la primera simulación de visita.
Publish-DemoEmail 'demo-visit-1' 'email.visit' $demoPayload
Publish-DemoEmail 'demo-missing-recipient' 'email.admitted' @{}
Publish-DemoEmail 'demo-unknown-type' 'email.unknown' $demoPayload

Invoke-RestMethod -Headers $demoHeaders -Uri "$demoApi/queues/%2F/q.cmd.email" |
  Select-Object name,messages_ready,messages_unacknowledged,message_stats
Invoke-RestMethod -Headers $demoHeaders -Uri "$demoApi/queues/%2F/q.cmd.email.dlq" |
  Select-Object name,messages_ready,consumers
```

Cada publicación debe devolver `routed: true`. Esperar al procesamiento y a la actualización de estadísticas: tres logs `email-simulado`, un INFO `duplicado` y dos ERROR `mensaje-muerto` con `intentos=0`. La cola principal debe quedar sin mensajes listos ni pendientes de ACK; las estadísticas de ACK deben reflejar también el duplicado. La DLQ debe contener dos mensajes y ningún consumidor. Puede inspeccionarse en Management, puerto 15679.

Para comprobar el agotamiento recuperable sin enviar correos reales: detener Notify, mantener el mismo broker dedicado y reiniciar con `EMAIL_MODE=smtp`, `EMAIL_FROM=avisos@example.com`, `SMTP_HOST=127.0.0.1`, `SMTP_PORT` apuntando a un puerto local confirmado sin servidor, y `SMTP_AUTH=false`. Publicar un nuevo `eventId`: tras los tres fallos debe aparecer `intentos=3` y un mensaje adicional en DLQ. No usar un servidor SMTP real para esta comprobación.

Al terminar, detener Notify con Ctrl+C y ejecutar `docker stop bd-ep2-email-demo`. Al usar `--rm` y ningún volumen compartido, se descarta únicamente el broker dedicado. No borrar ni vaciar colas del clúster del proyecto.

## Validación manual de cuadrillas con broker local aislado

Validación manual completada en local. Se conserva el procedimiento para repetirla; no forma parte de los tests automatizados. Usa un broker dedicado, sin volúmenes compartidos y con puertos distintos del clúster y de la demo de emails. Las credenciales son ficticias y exclusivas de esta prueba. Usar consolas PowerShell dedicadas; no apuntar a un broker compartido.

En la primera consola:

```powershell
docker run --rm -d --name bd-ep2-crew-demo `
  -p 127.0.0.1:5680:5672 -p 127.0.0.1:15680:15672 `
  -e RABBITMQ_DEFAULT_USER=ep2-crew-demo -e RABBITMQ_DEFAULT_PASS=solo-demo-local `
  rabbitmq:3.12-management-alpine
docker exec bd-ep2-crew-demo rabbitmq-diagnostics -q ping
```

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:RABBIT_ADDRESSES = 'localhost:5680'
$env:RABBIT_USER = 'ep2-crew-demo'
$env:RABBIT_PASSWORD = 'solo-demo-local'
$env:EMAIL_MODE = 'logging'
$env:SERVER_PORT = '8083'
.\mvnw.cmd spring-boot:run
```

Si reproduce el fallo del wrapper descrito antes, usar su distribución instalada con el objetivo `spring-boot:run`. En una segunda consola, comprobar health y preparar la publicación. La routing key queda fija en `crew.ticket`, incluso para probar un `type` desconocido:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
$crewApi = 'http://127.0.0.1:15680/api'
$crewAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('ep2-crew-demo:solo-demo-local'))
$crewHeaders = @{Authorization = "Basic $crewAuth"}

function Publish-DemoCrew($eventId, $type, $payload) {
    $envelope = @{type=$type; eventId=$eventId; payload=$payload} | ConvertTo-Json -Depth 5 -Compress
    $publication = @{
        properties=@{delivery_mode=2; content_type='application/json'}
        routing_key='crew.ticket'
        payload=$envelope
        payload_encoding='string'
    } | ConvertTo-Json -Depth 5 -Compress
    Invoke-RestMethod -Method Post -Uri "$crewApi/exchanges/%2F/cmd.direct/publish" `
      -Headers $crewHeaders -ContentType 'application/json; charset=utf-8' `
      -Body ([Text.Encoding]::UTF8.GetBytes($publication))
}

$crewPayload = @{
    requestId='caso-demo-1'; procedureType='bache'
    procedureLabel='Bache en calle'; address='Los Aromos 120'
}
Publish-DemoCrew 'demo-crew-1' 'crew.ticket' $crewPayload
```

Cada publicación debe responder `routed: true`: confirma enrutamiento, no procesamiento. Esperar a observar el primer INFO `crew-ticket` con número `CREW-demo-crew-1` antes de publicar el duplicado y los casos inválidos:

```powershell
Publish-DemoCrew 'demo-crew-1' 'crew.ticket' $crewPayload

$missingAddress = $crewPayload.Clone()
$missingAddress.Remove('address')
Publish-DemoCrew 'demo-crew-sin-address' 'crew.ticket' $missingAddress

$invalidRequest = $crewPayload.Clone()
$invalidRequest.requestId = 123
Publish-DemoCrew 'demo-crew-request-invalido' 'crew.ticket' $invalidRequest

$invalidType = $crewPayload.Clone()
$invalidType.procedureType = '   '
Publish-DemoCrew 'demo-crew-tipo-vacio' 'crew.ticket' $invalidType

$invalidLabel = $crewPayload.Clone()
$invalidLabel.procedureLabel = @{nombre='etiqueta incorrecta'}
Publish-DemoCrew 'demo-crew-label-invalida' 'crew.ticket' $invalidLabel

Publish-DemoCrew 'demo-crew-type-desconocido' 'crew.unknown' $crewPayload

Invoke-RestMethod -Headers $crewHeaders -Uri "$crewApi/queues/%2F/q.cmd.crew" |
  Select-Object name,messages_ready,messages_unacknowledged,message_stats
Invoke-RestMethod -Headers $crewHeaders -Uri "$crewApi/queues/%2F/q.cmd.crew.dlq" |
  Select-Object name,messages_ready,consumers
```

Los casos independientes necesitan `eventId` diferentes; reutilizar uno ya procesado puede activar la deduplicación antes de validar el contenido. Para repetir casos en la misma instancia, usar identificadores nuevos salvo en la comprobación intencional del duplicado.

Esperar al procesamiento y a la actualización de estadísticas. Resultado esperado: un INFO `crew-ticket`, un INFO `duplicado cola=q.cmd.crew` y cinco ERROR `mensaje-muerto` con `intentos=0`. La cola principal queda sin mensajes listos ni pendientes de ACK, con dos ACK por el válido y su duplicado. La DLQ contiene cinco mensajes y ningún consumidor; puede inspeccionarse en Management, puerto 15680.

El agotamiento de reintentos se verifica mediante el fallo controlado de las pruebas automatizadas. Detener RabbitMQ no demuestra un fallo recuperable del servicio de tickets. No se añaden interruptores ni mecanismos de fallo en producción.

Al terminar, detener únicamente la instancia Notify iniciada aquí con Ctrl+C en su consola y ejecutar `docker stop bd-ep2-crew-demo`. El contenedor usa `--rm` y ningún volumen compartido. Cerrar las consolas dedicadas para descartar sus variables de entorno. No borrar ni vaciar colas del clúster del proyecto ni detener otros recursos.

## Arranque

No usa base de datos. Necesita el clúster de `barriodigital-infra/mq` arriba.

```powershell
# en barriodigital-infra
copy mq\.env.example mq\.env
# editar mq\.env
docker compose -f mq/compose.yml up -d

# en ms-barriodigital-notify, con la password de mq\.env
$env:RABBIT_PASSWORD="..."
.\mvnw.cmd spring-boot:run
```

```powershell
curl http://localhost:8083/actuator/health
```

Responde `{"status":"UP"}`.

En el Management UI ([http://127.0.0.1:15672](http://127.0.0.1:15672), usuario y password de `mq\.env`):

- Exchanges: `cmd.topic`, `cmd.direct` y `cmd.dead.dlx`.
- Queues: `q.cmd.email`, `q.cmd.crew`, `q.cmd.certificate` y sus tres `.dlq`.
- En cada cola principal, la pestaña Bindings muestra un binding desde `cmd.direct` y otro desde `cmd.topic`. En cada DLQ, uno desde `cmd.dead.dlx`.

También por consola:

```powershell
docker exec rabbitmq-1 rabbitmqctl list_exchanges
docker exec rabbitmq-1 rabbitmqctl list_queues name durable arguments
docker exec rabbitmq-1 rabbitmqctl list_bindings
```

## Docker

La imagen no lleva `.env`. `.dockerignore` deja fuera `target/` y `.env`. Las variables `RABBIT_*` van al contenedor.

```powershell
docker build -t bd-notify .

docker run --rm -p 8083:8083 `
  -e RABBIT_ADDRESSES=host.docker.internal:5672 `
  -e RABBIT_PASSWORD=******** `
  bd-notify
```

`GET http://localhost:8083/actuator/health` responde `{"status":"UP"}` cuando conectó al broker.

`host.docker.internal` aplica a Docker Desktop y solo alcanza el nodo 1, que es el único que publica el `5672` en el host. En compose se usarán los dos nodos con EP2-28.

## Git

`.gitignore` cubre `target/` y `.env`. Los secretos solo van en `.env`.
