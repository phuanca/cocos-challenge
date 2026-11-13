# Cocos challenge backend

API REST para buscar instrumentos, consultar portfolios y operar órdenes.

## Estado

- Búsqueda de instrumentos: implementada.
- Portfolio: implementado.
- Órdenes MARKET/LIMIT y cancelación: implementadas.
- Tests de integración con PostgreSQL mediante Testcontainers.
- Trazas con OpenTelemetry/OTLP; exportación desactivada por defecto.

## Ejecución

- Requiere Java 21, Maven y Docker.
- Copiar `.env.example` como `.env`.
- Base local con datos: `docker compose up -d`.
- Aplicación: `mvn spring-boot:run`.
- Tests: `mvn test`.
- Casos manuales documentados: `api.http`.
- Reiniciar los datos: `docker compose down -v` y volver a ejecutar `docker compose up -d`.

## Decisiones

- Java 21 + Spring Boot 4, según la indicación recibida para esta instancia.
- JDBC y SQL directo; sin ORM.
- Posiciones reconstruidas desde órdenes `FILLED`; no existe una tabla de posiciones.
- Órdenes `NEW` reservan pesos o acciones.
- MARKET se ejecuta con el último `close`; LIMIT queda en `NEW`.
- Rendimiento de posición abierta contra costo promedio móvil.
- Retorno diario con `close` y `previousClose`.
- El usuario se identifica en la URL; el challenge no requiere autenticación.
- Observabilidad como requisito operativo: Actuator + OpenTelemetry, sin incluir datos sensibles en spans.
- Errores con código estable y `traceId`; logs correlacionados y métrica `application.errors` por código/status.

## Observaciones y posibles evoluciones

- El esquema no contempla idempotencia: considerar `Idempotency-Key`, hash del request y unicidad por usuario en PostgreSQL.
- Creación y cancelación son transaccionales y bloquean la fila del usuario con `FOR UPDATE`.
- El lock serializa operaciones de una misma cuenta, no de toda la plataforma.
- Para reducir contención: evaluar locks por saldo/posición, updates condicionales u optimistic locking.
- El portfolio recorre el historial del usuario: agregar índices y medir con `EXPLAIN ANALYZE` antes de optimizar.
- Posible evolución: movimientos auditables + saldos, posiciones y reservas materializados.
- Una caché puede acelerar lecturas; no debería ser la fuente para validar fondos o acciones.
- Integración real con mercado: reserva local, envío asíncrono, confirmación o compensación y reconciliación.
- Búsqueda a mayor escala: evaluar `pg_trgm` + GIN después de medir.
- El seed deja el instrumento 31 en `-10`: se expone y valúa como inconsistencia, se registra un warning y las nuevas ventas siguen siendo rechazadas.
- Faltan constraints de dominio en la base: cantidades/precios positivos y valores válidos para side/type/status.
- En producción: definir sampling, retención, alertas y un collector OTLP; la instrumentación no reemplaza métricas de negocio.

## Endpoints

- `GET /api/instruments?q=GGAL`
- `GET /api/users/{userId}/portfolio`
- `POST /api/users/{userId}/orders`
- `POST /api/users/{userId}/orders/{orderId}/cancel`
