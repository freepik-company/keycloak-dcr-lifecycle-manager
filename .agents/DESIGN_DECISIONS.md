# Estrategia de Vinculación y Limpieza de Clientes DCR en Keycloak

## Problema
El Registro Dinámico de Clientes (DCR) utilizado por plataformas y MCPs (como ChatGPT y Claude) genera un volumen masivo de clientes. Muchos de estos clientes quedan huérfanos una vez finaliza la sesión del usuario. Además, en el realm conviven clientes creados manualmente con clientes DCR, por lo que la limpieza debe ser muy selectiva.

## Plan de Acción Propuesto

Para gestionar esto de forma segura en un entorno de Alta Disponibilidad (HA) y diferenciar los tipos de clientes, implementaremos una estrategia de dos fases dentro del mismo proyecto Java (usando SPIs de Keycloak):

### [x] Fase 1: Marcado en tiempo de creación y Limpieza "en vivo" (Event Listener)

1.  **Interceptar la creación (AdminEvent `CREATE` CLIENT):**
    *   Cuando se crea un cliente, no sabemos de quién es, pero **sabemos que acaba de nacer**.
    *   El EventListener intercepta este evento y le añade un atributo al cliente recién creado: `dcr_created_at = <timestamp_actual>`.
    *   Generamos un **Fingerprint (Huella Compuesta y Determinista)**: 
        1. Para cada `redirect_uri` proporcionada por el cliente, extraemos su esquema y su host (ej. `https://claude.ai`, `http://localhost`, `cursor://auth`). Esto cubre custom schemes de apps de escritorio.
        2. Eliminamos duplicados y ordenamos alfabéticamente esta lista de orígenes.
        3. Lo concatenamos todo junto al `clientName` del cliente. (Ej: `https://claude.ai|Claude`, `http://localhost,cursor://auth|Cursor IDE`).
        4. Guardamos este valor (o un hash SHA-256 del mismo si es muy largo) en el atributo `dcr_fingerprint`.
    *   *Objetivo cumplido:* Ya sabemos diferenciar un cliente DCR de uno manual, y además los tenemos "agrupados" genéricamente por el dominio de su callback.

2.  **Interceptar el Login (Event `LOGIN`):**
    *   El usuario se loga. El EventListener captura el evento.
    *   Extraemos el `clientId` y buscamos el cliente. ¿Tiene la marca `dcr_created_at`? Si no la tiene, ignoramos (es un cliente manual).
    *   Si la tiene, le añadimos un segundo atributo: `linked_user_id = <userId>`.
    *   **Limpieza en vivo:** En ese mismo instante, buscamos en Keycloak todos los clientes que cumplan:
        *   `linked_user_id == <userId>`
        *   `dcr_fingerprint == <fingerprint_del_cliente_actual>`
        *   `clientId != <current_clientId>`
    *   Borramos esos clientes (Ej: Borramos los viejos de ChatGPT, pero dejamos los de Claude).

### [x] Fase 2: Limpieza de "Huérfanos Puros" (Timer Task con soporte HA)

¿Qué pasa con los clientes que se crearon por DCR pero el usuario nunca llegó a logarse? Se quedan con la marca `dcr_created_at` pero sin `linked_user_id`.

1.  **Crear una Scheduled Task (TimerProvider):**
    *   Se ejecutará periódicamente (ej. cada hora).
2.  **Asegurar Alta Disponibilidad (ClusterProvider):**
    *   Al despertar, la tarea intentará obtener un cerrojo distribuido (Distributed Lock) usando `session.getProvider(ClusterProvider.class).executeIfNotExecuted("dcr-cleanup-task", timeout)`.
    *   Esto garantiza que, aunque haya 3 nodos de Keycloak, solo 1 ejecuta la limpieza.
3.  **Lógica de Borrado:**
    *   Buscar clientes que tengan `dcr_created_at`.
    *   Filtrar los que **NO** tengan `linked_user_id`.
    *   Comprobar si el tiempo transcurrido desde `dcr_created_at` es mayor a un "periodo de gracia" (ej. 24 horas).
    *   Borrar los que cumplan esta condición.

---
Ambas fases han sido implementadas en el código (`DcrLifecycleEventListenerProvider` y `DcrOrphanCleanupTask`).