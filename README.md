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

## Estado (EP2-14)

Conecta al broker y, al abrir la conexión, declara la topología del contrato EP2-02: 3 exchanges, 6 colas y 9 bindings. Los consumidores, el ACK manual y los reintentos llegan con EP2-15 a EP2-18.

La conexión no se abre al arrancar: se abre con el primer uso. Hoy, sin consumidores, ese uso es el primer `GET /actuator/health`.

El health de RabbitMQ está activo. `/actuator/health` en `UP` significa que notify se conectó al broker, y la topología se declara en esa misma conexión. Sin broker responde `DOWN`.

## Variables

Se leen del entorno o de un `.env` en la raíz del módulo (plantilla en `.env.example`).

| Variable | Por defecto | Qué es |
|---|---|---|
| `RABBIT_ADDRESSES` | `localhost:5672` | Nodos separados por coma |
| `RABBIT_USER` | `barriodigital` | Usuario del broker |
| `RABBIT_PASSWORD` | ninguno | Password del broker. Sin ella la app arranca, pero no se conecta y el health queda `DOWN` |

En local solo el nodo 1 del clúster publica el `5672` en el host. El nodo 2 no abre puertos, por eso el valor por defecto lleva solo `localhost`. Dentro de la red del compose del clúster se pueden poner los dos: `rabbitmq-1:5672,rabbitmq-2:5672`.

El timeout de conexión es de 2000 ms (`spring.rabbitmq.connection-timeout`).

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

Los tests no necesitan broker. Revisan los nombres, tipos y argumentos de la topología, que la app no arranque si falta una clave, y que el contexto levante. `src/test/resources/application.properties` apaga el health de RabbitMQ y pone una password de prueba.

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
