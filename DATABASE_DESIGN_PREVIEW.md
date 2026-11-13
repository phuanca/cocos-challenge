# Propuesta de evolución de datos

Este documento anticipa los cambios de esquema propuestos para soportar más operaciones concurrentes, lecturas eficientes y recuperación confiable. No es todavía una migración aprobada ni describe comportamiento ya implementado.

## Objetivos

- Conservar en Postgres un registro durable y auditable de cada movimiento.
- Evitar reconstruir caja y posiciones recorriendo todo el historial en cada consulta.
- Mantener saldos actuales para lecturas rápidas y validaciones de recursos.
- Usar Redis como proyección aceleradora, siempre reconstruible desde Postgres.
- Evitar aplicar dos veces una operación aunque haya reintentos o fallos parciales.
- Serializar conflictos por cuenta, sin bloquear operaciones de otras cuentas.

## Cambios propuestos

### 1. Ledger inmutable (`ledger_entries`)

Registra los efectos financieros como movimientos append-only: efectivo, cantidad de instrumentos o reservas. Cada fila lleva cuenta, secuencia, operación origen, orden/instrumento cuando aplique y deltas.

Resuelve auditoría y reconstrucción: los saldos pueden recalcularse a partir de movimientos; no se pierde la historia al cancelar o compensar. Las correcciones se representan con movimientos compensatorios.

```sql
CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES users(id),
    sequence BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    cash_delta NUMERIC(20, 2) NOT NULL DEFAULT 0,
    quantity_delta NUMERIC(20, 0) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, sequence)
);
```

La inmutabilidad debe reforzarse con permisos del rol de aplicación y/o trigger que rechace `UPDATE` y `DELETE`. Un tipo `ADJUSTMENT` no autoriza editar filas: registra un nuevo movimiento compensatorio.

### 2. Saldos materializados (`account_balances`, `position_balances`)

Guardan el estado vigente para leer el portfolio y validar operaciones con consultas indexadas y pocas sumas. Cada actualización de saldo debe ocurrir en la misma transacción Postgres que inserta su ledger correspondiente.

```sql
SELECT cash_balance, sequence
FROM account_balances
WHERE account_id = :accountId;

SELECT instrument_id, settled_quantity, reserved_quantity
FROM position_balances
WHERE account_id = :accountId;
```

La cantidad neta debe permitir valores negativos si se necesita preservar y exponer inconsistencias históricas, como el -10 de BMA del seed actual. El saldo disponible y las reglas para aceptar operaciones deben distinguir claramente posición liquidada de cantidad reservada. Las restricciones deben impedir reservas negativas, pero no borrar/ocultar cantidades históricas negativas.

### 3. Idempotencia e intención durable (`operation_requests`)

Persiste la solicitud antes de los efectos externos. La unicidad `(account_id, idempotency_key)` hace que un reintento recupere la operación original en lugar de duplicarla. `request_hash` detecta reutilización de la misma clave con contenido distinto.

```sql
UNIQUE (account_id, idempotency_key)
```

El estado permite distinguir una solicitud durable pendiente de una aplicada o rechazada. La decisión de cuándo una orden pendiente reserva efectivo/acciones debe quedar fijada antes de implementar el flujo: si la reserva se difiere hasta Redis, dos operaciones pendientes podrían comprometer los mismos recursos.

### 4. Entrega recuperable (`outbox_events`)

El evento outbox se inserta junto con la intención en una transacción Postgres. Un worker lo entrega a Redis; puede reintentarlo después de fallos.

```sql
INSERT INTO outbox_events (operation_id, account_id, sequence, event_type, payload)
VALUES (:operationId, :accountId, :sequence, :eventType, CAST(:payload AS jsonb));
```

Cada evento tiene identidad/secuencia única por cuenta. El consumidor en Redis debe ser idempotente: repetir un evento ya aplicado no vuelve a afectar el saldo. Estados `PENDING`, `PROCESSING`, `DONE` y `FAILED` describen entrega, no sustituyen el resultado financiero durable de la operación.

### 5. Proyección Redis y reconstrucción

Redis contiene saldos/reservas de acceso rápido, no la única copia del estado. Una pérdida de Redis se resuelve reconstruyendo cada cuenta desde los saldos Postgres y aplicando los eventos posteriores a la secuencia del snapshot.

`projection_checkpoints` propone guardar el último número aplicado. Para evitar huecos o mezclar una foto con eventos concurrentes, la reconstrucción debe leer un snapshot consistente de saldos y secuencia, cargarlo en Redis y reproducir desde la secuencia siguiente. La actualización del checkpoint y el tratamiento de eventos deben ser idempotentes.

## Consistencia y consultas

- **Postgres es durable**: ledger, intención, saldos materializados y outbox viven allí.
- **Redis es reemplazable**: solo se consulta para optimizar decisiones de baja latencia; debe poder reconstruirse.
- **Lecturas de portfolio**: consultan caja y posiciones materializadas, y hacen join con el último precio de mercado. El historial de fills se consulta solo para métricas que dependan del costo promedio o rendimiento.
- **Concurrencia**: cambios de una misma cuenta asignan secuencia y actualizan ledger/saldos dentro de una transacción con bloqueo por cuenta o control optimista equivalente. Cuentas distintas avanzan en paralelo.
- **Fallos parciales**: outbox permite retomar entregas; idempotencia y secuencia evitan duplicados y eventos fuera de orden.

## Decisiones que deben cerrarse antes de migrar

- Si una operación aceptada como `PENDING` reserva inmediatamente recursos en el saldo durable. La opción más segura es reservar en Postgres al crear la intención y reflejar esa reserva en Redis mediante outbox.
- Si una caída de Redis deriva a Postgres en modo transaccional o si se conserva la operación como pendiente para el worker. El fallback debe usar las mismas invariantes e idempotencia.
- Política de posiciones negativas: permitirlas para reflejar legado/inconsistencias y alertar, o imponer no-negatividad desde el corte, con tratamiento explícito para datos históricos.
- Retención y archivado del ledger/outbox, volumen esperado e índices/particionado. No particionar antes de medir consultas y crecimiento.
- Migración inicial: calcular saldos desde órdenes `FILLED`, reservas desde órdenes `NEW`, guardar totales de reconciliación y validar antes del cambio de lecturas.

## Secuencia de implementación sugerida

1. Acordar invariantes, estados y semántica de reservas; revisar el DDL propuesto.
2. Crear migración aditiva y backfill desde `orders`, con reporte de diferencias y validaciones.
3. Escribir ledger y saldos en Postgres dentro de transacciones; mantener lecturas antiguas y comparar resultados.
4. Cambiar portfolio/validaciones a saldos materializados después de reconciliar.
5. Añadir outbox, idempotencia y worker Redis; probar reintentos, fallos y reconstrucción.
6. Medir latencia, carga de DB, contención por cuenta y crecimiento antes de ajustar índices o particiones.
