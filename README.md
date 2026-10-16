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

## Estado (EP2-13)

Esqueleto: arranca, expone `/actuator/health` y no conecta al broker. La conexión, la topología y los consumidores llegan con EP2-14 a EP2-18.

El starter de AMQP trae el health de RabbitMQ. Sin broker dejaría el health en DOWN, así que `management.health.rabbit.enabled` está en false hasta EP2-14.

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

Los tests levantan el contexto sin broker y revisan que `GET /actuator/health` responda `UP`.

## Arranque

No necesita `.env` ni base de datos.

```powershell
.\mvnw.cmd spring-boot:run
```

```powershell
curl http://localhost:8083/actuator/health
```

Responde `{"status":"UP"}`.

## Docker

La imagen no lleva `.env`. `.dockerignore` deja fuera `target/` y `.env`.

```powershell
docker build -t bd-notify .

docker run --rm -p 8083:8083 bd-notify
```

`GET http://localhost:8083/actuator/health` responde `{"status":"UP"}`.

## Git

`.gitignore` cubre `target/` y `.env`. Los secretos solo van en `.env`.
