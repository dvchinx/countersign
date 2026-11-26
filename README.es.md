<h1 align="center">Countersign</h1>

<p align="center">
  <b>Tu agente propone. Countersign decide.</b>
</p>

<p align="center">
  Guardrails en tiempo de ejecución para agentes de Spring AI: llamadas a herramientas (incluido MCP) controladas por políticas, aprobadas por humanos y firmadas.<br/>
  Fail-closed y auditable. Una sola dependencia starter, sin sidecar en Python.
</p>

<p align="center">
  <img alt="Estado: pre-alpha" src="https://img.shields.io/badge/estado-pre--alpha-orange">
  <a href="https://central.sonatype.com/artifact/io.github.dvchinx/countersign-spring-boot-starter"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.dvchinx/countersign-spring-boot-starter"></a>
  <a href="https://github.com/dvchinx/countersign/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/dvchinx/countersign/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/java-21-blue?logo=openjdk&logoColor=white">
  <img alt="Spring AI 2.0 and 2.1" src="https://img.shields.io/badge/spring%20ai-2.0.x%20%7C%202.1.x-6DB33F?logo=spring&logoColor=white">
  <img alt="Licencia: Apache 2.0" src="https://img.shields.io/badge/licencia-Apache%202.0-blue">
  <a href="https://github.com/dvchinx/countersign/stargazers"><img alt="Estrellas en GitHub" src="https://img.shields.io/github/stars/dvchinx/countersign?style=social"></a>
</p>

<p align="center"><a href="README.md">English</a> · <b>Español</b></p>

---

En derecho, una *contrafirma* es la segunda firma que le da validez a un documento.
Countersign le pone esa segunda firma a las acciones de tu agente de IA: cada llamada a una
herramienta que propone el modelo se evalúa contra tus políticas, pasa por un humano cuando hace
falta y queda registrada en un **recibo** firmado con Ed25519 y encadenado por hash, antes de que se
ejecute nada.

Piensa en él como **Spring Security para las llamadas a herramientas de un agente**. Funciona con
métodos `@Tool` de Spring AI, `ToolCallback`s y herramientas MCP, y no tienes que modificar tus
herramientas.

## Demo

El ejemplo [`banking-agent`](examples/banking-agent), grabado mientras se ejecuta:

<p align="center">
  <img src="docs/images/banking-demo.gif" width="860"
       alt="Grabación de terminal de la demo del agente bancario. Alice pide al agente mover 5.000 dólares a ACME Corp; Countersign retiene la llamada payments_transfer hasta que la apruebe una segunda persona. Alice no puede aprobar su propia petición; ops@bank.example la aprueba y la transferencia se ejecuta. Después Alice pide borrar la tabla de transacciones; Countersign lo deniega por la política no-destructive-sql. Cada llamada deja un recibo firmado, y los dos recibos se listan, encadenados, y se verifican.">
</p>

[Diagrama estático del mismo flujo](docs/images/banking-demo.drawio.svg).

El modelo nunca ve un stack trace. Recibe un rechazo estructurado que puede explicarle al usuario,
y tú obtienes un recibo que puedes verificar después.

Pruébalo en dos minutos, sin API key y sin Docker: `./mvnw -pl examples/banking-agent -am install -DskipTests &&
./mvnw -pl examples/banking-agent spring-boot:run`, y sigue el [recorrido del ejemplo](examples/banking-agent/README.md)
(en inglés).

## Inicio rápido

> **Pre-alpha:** la `0.1.0` es la primera versión publicada. La API todavía puede cambiar antes de la `1.0`; cada cambio
> incompatible aparece en el [CHANGELOG](CHANGELOG.md).

**1. Agrega el starter**

```xml
<dependency>
  <groupId>io.github.dvchinx</groupId>
  <artifactId>countersign-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

**2. Escribe algunas políticas** en `application.yml`

```yaml
countersign:
  default-decision: deny                 # fail closed: todo lo que no esté permitido se bloquea
  policies:
    - id: no-destructive-sql
      tools: ["db_*"]
      when: "#args['query'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"
      decision: deny
    - id: transfers-need-approval
      tools: ["payments_transfer"]
      when: "#args['amount'] > 1000"
      decision: require-approval
```

**3. Listo.** Tus herramientas existentes se envuelven automáticamente:

```java
@Tool(name = "payments_transfer", description = "Transfer money between accounts")
@RequiresApproval(reason = "Moves real money")   // opcional: política por herramienta como anotación
public TransferResult transfer(String from, String to, BigDecimal amount) { ... }
```

Al arrancar verás una línea en el log que indica que Countersign está activo y qué decisión usa por defecto.

## Qué pasa en una llamada a herramienta

<p align="center">
  <img src="docs/images/tool-call-flow.drawio.svg" width="860"
       alt="Flujo de una llamada: el agente propone la llamada, GuardedToolCallback consulta al PolicyEngine, que devuelve ALLOW, REQUIRE APPROVAL o DENY. Cada decisión, con la respuesta del humano cuando hacía falta aprobación, genera un recibo firmado antes de que la herramienta se ejecute, que va al AuditSink y a Micrometer. Las llamadas permitidas o aprobadas pasan sus argumentos por cada regla de redacción que coincida, ejecutan la herramienta y pasan su resultado por cada regla que coincida; las denegadas, rechazadas o vencidas reciben un rechazo estructurado. Cualquiera de los dos vuelve al modelo. Una nota aclara que la redacción no es una decisión: toda regla que coincide se aplica a cada llamada que se ejecuta, también a las aprobadas.">
</p>

1. **Construye** un `ToolInvocation`: nombre de la herramienta, argumentos parseados, principal e id de conversación.
2. **Evalúa** la invocación contra tus políticas, en orden de prioridad. Gana la primera que coincide; si ninguna coincide, se aplica la decisión por defecto (`deny`). Con `REQUIRE_APPROVAL`, espera a un humano hasta que venza el plazo.
3. **Registra** un recibo: la decisión, las políticas que coincidieron, quién la aprobó, las reglas de redacción que se aplican, un hash con clave (HMAC-SHA256) de los argumentos (nunca los valores en crudo), el hash del recibo anterior y una firma Ed25519.
4. **Publica** el recibo en tu `AuditSink`, junto con métricas y observaciones de Micrometer.
5. **Aplica** la decisión: ejecuta la herramienta si se permitió o se aprobó, pasando sus argumentos y su resultado
   por cada regla de redacción que coincida; si no, devuelve un rechazo estructurado.

El recibo se firma antes de que la herramienta se ejecute: si la firma falla, la herramienta no corre.

El control ocurre **donde se ejecuta la herramienta**, no en la lista de herramientas que se le envía
al modelo. Una herramienta que nunca se le ofreció al modelo, o una que lo engañaron para llamar,
igual tiene que pasar por el motor de políticas.

## Comparación

Countersign no filtra prompts ni respuestas del modelo. Gobierna **acciones**. Úsalo junto con un
filtro de contenido, no en lugar de uno.

|                                                   | **Countersign** | Filtros de prompt / respuesta | Sidecar de guardrails en Python | `if` dentro de cada `@Tool` |
|---------------------------------------------------|:---------------:|:-----------------------------:|:-------------------------------:|:---------------------------:|
| Controla la llamada a la herramienta en sí        | ✅              | ❌                            | ⚠️ depende de cómo se integre   | ✅                          |
| Corre en tu JVM, sin servicio adicional           | ✅              | ✅                            | ❌                              | ✅                          |
| Cubre herramientas MCP                            | ✅              | ❌                            | ⚠️                              | ❌ (no es tu código)        |
| Aprobación humana incluida                        | ✅              | ❌                            | ⚠️                              | ❌ hazlo tú mismo           |
| Auditoría firmada y encadenada por hash           | ✅              | ❌                            | ❌                              | ❌                          |
| Determinista (sin LLM en la decisión)             | ✅              | ⚠️ a menudo usa un LLM        | ⚠️ a menudo usa un LLM          | ✅                          |
| Fail-closed por defecto                           | ✅              | ⚠️                            | ⚠️                              | ⚠️ fácil de olvidar         |
| Políticas separadas del código de negocio         | ✅              | ✅                            | ✅                              | ❌                          |

## Funcionalidades

- **Tres decisiones:** `ALLOW`, `DENY`, `REQUIRE_APPROVAL`.
- **Redacción:** enmascara números de tarjeta, correos o cualquier campo en el resultado de una herramienta antes de
  que llegue al modelo, o en sus argumentos antes de que lleguen a la herramienta. Se aplica toda regla que coincida,
  también en llamadas aprobadas ([ADR 0015](docs/adr/0015-redaction.md)).
- **Políticas declarativas:** globs de herramientas (`db_*`), prioridades y condiciones escritas en un
  SpEL aislado (`SimpleEvaluationContext`: sin referencias a tipos, sin acceso a beans, sin llamadas a métodos arbitrarios).
- **Anotaciones:** `@RequiresApproval` en tus métodos `@Tool`. `@ToolPolicy(id = "...")` está previsto.
- **Humano en el ciclo:** un `ApprovalGateway` intercambiable. Countersign espera hasta un plazo, deniega si nadie responde y guarda en el recibo quién aprobó.
- **Quién lo pidió:** con Spring Security, el usuario autenticado es el principal de la llamada, en las políticas
  (`#principal`) y en el recibo, sin escribir código. Cualquier otra fuente se conecta con `PrincipalResolver`.
- **Recibos a prueba de manipulación:** JSON canónico, cadena de hashes SHA-256 y firmas Ed25519 usando solo
  `java.security`, más una utilidad de verificación que detecta cualquier recibo editado o borrado.
- **Privacidad por defecto:** los recibos guardan un hash con clave (HMAC) de los argumentos, así que los valores no se pueden recuperar probando candidatos. Los valores en crudo nunca se guardan.
- **Destinos de auditoría:** en memoria y PostgreSQL (solo inserción) incluidos.
- **Observabilidad:** `countersign.decisions`, `countersign.evaluation`, `countersign.redactions` y
  `countersign.audit.failures` vía Micrometer,
  y la decisión y el id del recibo en el span de cada llamada a herramienta.
- **Utilidades de test:** `countersign-test` te permite comprobar en tus propios tests que tu agente fue bloqueado.

### Un recibo

```json
{
  "streamId": "banking-agent-7c1e",
  "sequence": 42,
  "invocationId": "3f6c2a9e-8d41-4b7a-9f0e-5c2d1b8a7e64",
  "tool": "payments_transfer",
  "argsHmac": "9b2f4c…e71a",
  "decision": "REQUIRE_APPROVAL",
  "reason": "Moves real money",
  "basis": "POLICY_MATCHED",
  "matchedPolicies": ["transfers-need-approval"],
  "approval": "APPROVED",
  "approver": "bob",
  "principal": "alice",
  "conversationId": "conv-7",
  "timestamp": "2026-11-18T14:02:11.482Z",
  "previousHash": "1b42…08aa",
  "hash": "7f3a…c91e",
  "signature": "q3C1…Ag=="
}
```

## Configuración

Todas las propiedades viven bajo `countersign.*`. Para empezar solo necesitas `policies`.

| Propiedad | Valor por defecto | Qué hace |
|---|---|---|
| `countersign.enabled` | `true` | `false` desactiva Countersign; al arrancar se avisa de que las herramientas no están protegidas. |
| `countersign.default-decision` | `deny` | Decisión cuando ninguna política coincide: `deny`, `allow` o `require-approval`. Cualquier valor distinto de `deny` genera un aviso. |
| `countersign.default-reason` | `No policy allows this call` | Motivo que recibe el modelo cuando la decisión por defecto rechaza la llamada. |
| `countersign.policies[n].id` | | Id único, se guarda en los recibos. |
| `countersign.policies[n].tools` | | Globs de nombres de herramienta: `db_*`, `payments_transfer`. |
| `countersign.policies[n].when` | siempre | SpEL aislado sobre `#args`, `#tool`, `#principal`, `#conversationId`. |
| `countersign.policies[n].decision` | | `allow`, `deny` o `require-approval`. |
| `countersign.policies[n].reason` | genérico | Se muestra al modelo (deny) o a quien aprueba (require-approval). |
| `countersign.policies[n].priority` | `0` | El número más bajo se evalúa primero; los empates respetan el orden de la lista. |
| `countersign.redactors.<nombre>.pattern` | | Expresión regular que se reemplaza en los valores de texto, y en los números cuyos dígitos coinciden. |
| `countersign.redactors.<nombre>.fields` | | Claves cuyo valor se reemplaza, a cualquier profundidad, sin distinguir mayúsculas. Usa `pattern` o `fields`. |
| `countersign.redactors.<nombre>.replacement` | `[REDACTED]` | Texto literal de reemplazo. |
| `countersign.redactions[n].id` | | Id único (compartido con los ids de políticas), guardado en los recibos. |
| `countersign.redactions[n].tools` | | Globs de nombres de herramienta. |
| `countersign.redactions[n].when` | siempre | SpEL aislado sobre los argumentos, como en las políticas. |
| `countersign.redactions[n].target` | | `args` (lo que recibe la herramienta), `result` (lo que recibe el modelo), o `args, result`. |
| `countersign.redactions[n].redactors` | | Redactores que se aplican, en orden: claves de `countersign.redactors` o nombres de beans `Redactor`. |
| `countersign.receipts.signing-key` | generada | Clave privada Ed25519, PKCS#8, Base64. |
| `countersign.receipts.args-hmac-key` | generada | 32 o más bytes aleatorios, Base64, para el hash con clave de los argumentos. |
| `countersign.receipts.stream-id` | nombre de la app + aleatorio | Id de la cadena de recibos de esta instancia. |
| `countersign.audit.strict` | `false` | Rechaza la llamada si su recibo no se puede guardar. |
| `countersign.audit.in-memory-capacity` | `10000` | Recibos que guarda el sink en memoria por defecto. |
| `countersign.audit.jdbc.initialize-schema` | `true` | Con `countersign-audit-jdbc`: crea la tabla de recibos al arrancar si no existe. |
| `countersign.approval.timeout` | `2m` | Cuánto espera una llamada la respuesta de una persona antes de denegarse. |
| `countersign.annotation-packages` | | Paquetes extra donde buscar `@RequiresApproval`; los paquetes de tu aplicación se escanean siempre. |

Una política o regla de redacción rota (sin herramientas, SpEL inválido, id duplicado, redactor desconocido, patrón
inválido) detiene la aplicación al arrancar con un mensaje que nombra la propiedad. Una errata nunca deja una herramienta sin proteger en silencio.

**Nombres de herramientas.** OpenAI y Anthropic solo aceptan nombres de herramienta con letras, dígitos, `_` y `-`
(hasta 64 caracteres), así que llama a una herramienta `payments_transfer`, no `payments.transfer`. Countersign acepta
cualquier nombre, incluidos los nombres con puntos de algunos servidores MCP, y `*` en un glob abarca cualquier
carácter.

**Claves para producción.** Sin claves, Countersign las genera al arrancar y lo avisa. Los recibos de esa ejecución
no se pueden verificar después de reiniciar. Genera claves reales una vez y pásalas como secretos:

```bash
openssl genpkey -algorithm ed25519 -outform DER | base64 -w0   # COUNTERSIGN_SIGNING_KEY
openssl rand -base64 32                                         # COUNTERSIGN_ARGS_HMAC_KEY
```

```yaml
countersign:
  receipts:
    signing-key: ${COUNTERSIGN_SIGNING_KEY}
    args-hmac-key: ${COUNTERSIGN_ARGS_HMAC_KEY}
```

**Guardar los recibos.** Por defecto los recibos se guardan en memoria, y se avisa al arrancar. Para guardarlos en
PostgreSQL (14 o posterior), añade el módulo JDBC junto al starter. Si la aplicación tiene un `DataSource`, se usa
automáticamente, y la tabla `countersign_receipt` se crea al arrancar si no existe:

```xml
<dependency>
    <groupId>io.github.dvchinx</groupId>
    <artifactId>countersign-audit-jdbc</artifactId>
</dependency>
```

La tabla solo acepta inserciones: unos triggers rechazan `UPDATE`, `DELETE` y `TRUNCATE`. Si gestionas el esquema
con Flyway o Liquibase, copia `schema-postgresql.sql` del jar y pon `countersign.audit.jdbc.initialize-schema=false`.
Al actualizar desde 0.1.0 se añade una columna `redactions`: con `initialize-schema=true` se añade al arrancar; si no,
añade antes esta migración, o fallará cada inserción:

```sql
ALTER TABLE countersign_receipt ADD COLUMN IF NOT EXISTS redactions text[] NOT NULL DEFAULT '{}';
```

Para comprobar un stream más tarde:

```java
ReceiptVerifier verifier = ReceiptVerifier.fromX509Base64(publicKey);
verifier.verify(jdbcAuditSink.stream(streamId));   // Valid, o el primer recibo que se modificó
```

Para enviar los recibos a otro sitio, define tu propio bean `AuditSink`. Cualquier bean de Countersign se reemplaza
igual, y los beans `Policy` se suman a las políticas configuradas.

**Métricas y trazas.** Con Spring Boot Actuator, Countersign publica en Micrometer sin configurar nada:

| Métrica | Tags | Para qué |
|---|---|---|
| `countersign.decisions` | `tool`, `decision`, `policy`, `basis`, `outcome`, `approval` | Alertar con `outcome=refuse`; ver aprobaciones que vencen. |
| `countersign.evaluation` | `tool` | Vigilar el tiempo de evaluación de políticas (no incluye la espera de una persona). |
| `countersign.redactions` | `tool`, `rule`, `target` | Ver qué reglas de redacción se aplican a qué herramientas. |
| `countersign.audit.failures` | `tool`, `stage` | Detectar recibos que no se firmaron (`receipt`) o no se guardaron (`sink`), y resultados retenidos porque no se pudieron redactar (`redaction`). |

Las trazas muestran `countersign.decision`, `countersign.outcome`, `countersign.policy` y `countersign.receipt` en el
span `spring.ai.tool` de Spring AI. Para reaccionar tú a las decisiones, define un bean `DecisionListener`: recibe
cada decisión, sin los argumentos.

**`@RequiresApproval` en el código.** La regla vive junto a la herramienta que protege, así que ningún cambio de
configuración puede quitarla. Un `allow` configurado no puede saltarse la aprobación, un `deny` configurado sigue
ganando, y si no hay ninguna política configurada para la herramienta, se puede usar con aprobación incluso con el
`deny` por defecto. Countersign encuentra la anotación al arrancar escaneando los paquetes de tu aplicación, así que
también funciona con objetos de herramientas que no son beans (`.tools(new BankTools())`). En un método sin `@Tool`,
detiene la aplicación en lugar de no hacer nada.

**Aprobación humana.** Una llamada `require-approval` espera la respuesta de un bean `ApprovalGateway`, como mucho
`countersign.approval.timeout`. Sin gateway se deniega al momento, y al arrancar se avisa. Para una pantalla de
aprobación dentro de tu app, usa el gateway en memoria y responde con la identidad del usuario autenticado:

```java
@Bean
InMemoryApprovalGateway approvals() {
    return new InMemoryApprovalGateway();
}

// En tu controlador de aprobaciones:
approvals.pending();                                // llamadas en espera, con herramienta, argumentos y motivo
approvals.approve(invocationId, currentUser());     // la herramienta se ejecuta; el recibo nombra a quien aprobó
approvals.reject(invocationId, currentUser(), "Beneficiario desconocido");
```

La petición del agente espera mientras la aprobación está pendiente, así que el plazo debe ser menor que cualquier
timeout HTTP que haya delante.

**Redacción.** Una llamada puede estar permitida y aun así llevar datos que no deben ir a donde van. Una regla de
redacción cambia el **resultado** antes de que llegue al modelo, como un número de tarjeta que devuelve un CRM, o los
**argumentos** antes de que lleguen a la herramienta, como el correo de un cliente enviado a un buscador externo. A
diferencia de las políticas, se aplica toda regla que coincida con una llamada que se ejecuta, sea cual sea la decisión
de acceso: una llamada aprobada también se redacta, y ningún `allow` anterior puede saltarse una regla.

```yaml
countersign:
  policies:
    - id: crm-reads
      tools: ["crm_*"]
      decision: allow
    - id: searches
      tools: ["web_search"]
      decision: allow
  redactors:
    card-number:
      pattern: "\\b\\d(?:[ -]?\\d){12,18}\\b"   # en textos, y en números cuyos dígitos coinciden
      replacement: "[CARD]"
    email:
      pattern: "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"
      replacement: "[EMAIL]"
    contact:
      fields: [email, phone]                    # el valor bajo estas claves, a cualquier profundidad
  redactions:
    - id: no-cards-to-the-model
      tools: ["crm_*"]
      target: result                            # args, result, o ambos: "args, result"
      redactors: [card-number, contact]
    - id: no-emails-to-search
      tools: ["web_search"]
      target: args
      redactors: [email]
```

De `crm_find_customer`, el modelo recibe `{"name":"Ana Pérez","email":"[REDACTED]","phone":"[REDACTED]","card":"[CARD]"}`.
Cuando el modelo le pide a `web_search` `orders of ana@example.com`, la herramienta recibe `orders of [EMAIL]`. Cada
recibo guarda las reglas que se aplicaron, como `"redactions": ["no-cards-to-the-model"]`. Para reglas propias, define
un bean `Redactor` y nómbralo en `redactors`; `Redactor.strings(text -> ...)` es la forma más corta de escribir uno.
Mantén los patrones simples: se ejecutan sobre datos que controla el modelo, y la llamada los espera.

Los *argumentos* se redactan antes de pedirle a nadie que apruebe la llamada. Las políticas, el aprobador y el hash de
argumentos del recibo conservan lo que propuso el modelo, y los redactores son deterministas, así que quien tenga los
argumentos originales puede recalcular lo que recibió la herramienta. La herramienta recibe los argumentos redactados
como JSON compacto, o la entrada del modelo sin cambios cuando ninguna regla cambió nada. Si un redactor de argumentos
falla, la llamada se rechaza y se registra, y la herramienta nunca se ejecuta. Comprueba que la herramienta siga
aceptando lo que recibe: un número reemplazado por `[REDACTED]` pasa a ser un texto.

Los *resultados* que son JSON se redactan como árbol, así que `fields` ve sus claves; cualquier otro texto se redacta
como una sola cadena. Si un redactor de resultados falla, la herramienta ya se ejecutó, así que su resultado se retiene:
el modelo recibe `"status":"WITHHELD"`, y `countersign.audit.failures` lo cuenta con `stage=redaction`. La redacción
solo controla por dónde pasan los datos; cuando una herramienta no debe ejecutarse en absoluto, usa `deny` o
`require-approval`.

**En nombre de quién actúa el agente.** Cada recibo guarda el principal, y las políticas pueden usarlo
(`#principal == 'alice'`). Si la aplicación usa Spring Security, es el usuario autenticado, sin escribir código; los
usuarios anónimos cuentan como desconocidos. Para fijarlo tú, por ejemplo en un proceso batch, ponlo en el contexto de
la herramienta, que siempre tiene prioridad:

```java
chatClient.prompt(question)
        .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "nightly-reconciliation"))
        .call();
```

El contexto de seguridad va ligado a un hilo. Con `.stream()`, Spring AI ejecuta las herramientas en un hilo de
Reactor, que solo lleva el usuario con `spring.reactor.context-propagation=auto`; si no, la llamada se registra sin
principal y Countersign avisa una vez. Para leer el principal de otro sitio, define un bean `PrincipalResolver`. Si
lanza una excepción, la llamada se rechaza.

**Qué queda protegido.** Toda herramienta que ejecute un `ChatClient` creado desde el `ChatClient.Builder`
autoconfigurado, se haya registrado como se haya registrado: `.tools(misHerramientas)`, `defaultTools(...)`,
`ToolCallbackProvider`s (incluidos clientes MCP) o la resolución por nombre de Spring AI. Un `ChatClient` que crees
tú con `ChatClient.create(model)` tiene su propio gestor de herramientas: envuélvelo con
`GuardedToolCallingManager.guard(...)` o inyecta `ChatClient.Builder`.

## Principios de diseño

1. **Fail closed.** Si falla la evaluación de políticas, la aprobación, la firma o la auditoría, la llamada se deniega. Nunca se ejecuta una herramienta en silencio después de un error.
2. **Determinista.** La misma entrada con las mismas políticas da la misma decisión. No hay llamadas a un LLM en el camino de decisión.
3. **Privado por defecto.** Los prompts, argumentos y resultados en crudo nunca se registran ni se guardan, salvo que lo actives.
4. **Sin sorpresas.** Sin políticas, el único efecto es la decisión por defecto documentada (`deny`), y el log de arranque lo dice.
5. **Núcleo sin frameworks.** `countersign-core` solo depende del JDK, JSpecify y una librería JSON, y un test de ArchUnit lo garantiza.
6. **Bajo overhead.** La evaluación de políticas nunca se bloquea esperando I/O.

## Módulos

| Módulo                            | Qué es                                                                |
|-----------------------------------|-----------------------------------------------------------------------|
| `countersign-core`                | Dominio en Java puro: modelo, motor de políticas, recibos, SPIs       |
| `countersign-spring-ai`           | `GuardedToolCallback` y envoltura automática de herramientas          |
| `countersign-spring-boot-starter` | Autoconfiguración y propiedades `countersign.*`                       |
| `countersign-audit-jdbc`          | `AuditSink` en PostgreSQL de solo inserción                           |
| `countersign-test`                | Utilidades de test y tests de contrato para las SPIs                  |
| `examples/banking-agent`          | Demo principal: un agente que consulta saldos y mueve dinero          |
| `examples/mcp-server-guarded`     | Protección de herramientas expuestas a través de un servidor MCP      |
| `compat/spring-ai-2.1`            | Tests sobre Spring AI 2.1 (confirmación de llamadas); no se publica   |

**Requisitos:** Java 21, Spring Boot 4.x, Spring AI 2.0.1 o superior. Probado con Spring AI 2.0 y 2.1. En la 2.1,
la confirmación de llamadas a herramientas de Spring AI funciona con Countersign: una llamada que una persona confirma
sigue pasando por tus políticas, y un `deny` de una política gana al `allow` de la persona.

## Hoja de ruta hacia la 0.1.0

- [x] `countersign-core`: modelo, `PolicyEngine`, condiciones SpEL aisladas
- [x] Recibos: JSON canónico, cadena SHA-256, firma Ed25519, verificación
- [x] `GuardedToolCallback` y envoltura automática de métodos `@Tool`
- [x] `ALLOW` / `DENY` / `REQUIRE_APPROVAL` con un gateway que deniega al vencer el tiempo
- [x] `@RequiresApproval`
- [x] Starter de Spring Boot y línea de arranque en el log
- [x] Destinos de auditoría en memoria y PostgreSQL
- [x] Métricas y observaciones de Micrometer
- [x] Ejemplo `banking-agent`
- [x] GIF de demo
- [x] Publicado en Maven Central

No está previsto para la `0.1.0`: clasificadores basados en LLM, un panel web, frameworks que no sean Spring, A2A ni un servidor remoto de políticas.

## Contribuir

Countersign está pensado para crecer a través de sus SPIs. Puedes hacer una contribución útil sin
tocar el motor de políticas:

- **`ApprovalGateway`:** aprobaciones por Slack, Teams, correo o una consola web
- **`AuditSink`:** Kafka, S3, OpenSearch o tu SIEM
- **`ReceiptSigner`:** firma respaldada por KMS / HSM
- **`Redactor`:** detectores de documentos de identidad, IBAN o secretos en las llamadas a herramientas
- **`PrincipalResolver`:** en nombre de quién actúa el agente, desde una cabecera del gateway, un JWT o la identidad
  de un cliente MCP

Pasar el test de contrato del puerto en `countersign-test` es el requisito para hacer merge (hoy existen
`AuditSinkContract` y `RedactorContract`; escribir los contratos de los otros puertos es una buena primera
contribución). Empieza por
[`CONTRIBUTING.md`](CONTRIBUTING.md) (en inglés); los issues con la etiqueta `good first issue` son el mejor punto
de partida. El código, los comentarios y los commits se escriben en inglés, pero puedes abrir issues y discusiones
en español. Para reportar una vulnerabilidad, consulta [`SECURITY.md`](SECURITY.md).

Si Countersign te parece útil, una ⭐ ayuda a que otros equipos de Spring lo encuentren.

## Licencia

Apache License 2.0. Consulta [`LICENSE`](LICENSE).
