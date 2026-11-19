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
  <img alt="Maven Central: 0.1.0 muy pronto" src="https://img.shields.io/badge/maven%20central-0.1.0%20muy%20pronto-lightgrey">
  <a href="https://github.com/dvchinx/countersign/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/dvchinx/countersign/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/java-21-blue?logo=openjdk&logoColor=white">
  <img alt="Spring AI 2.0" src="https://img.shields.io/badge/spring%20ai-2.0.x-6DB33F?logo=spring&logoColor=white">
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

> El GIF grabado llega con la `0.1.0`. Esto es lo que muestra el ejemplo [`banking-agent`](examples/banking-agent):

<p align="center">
  <img src="docs/images/banking-demo.drawio.svg" width="860"
       alt="Demo del agente bancario: una transferencia de 5.000 dólares necesita aprobación humana y luego se ejecuta; una petición DROP TABLE es denegada por una política. Cada paso genera un recibo firmado.">
</p>

El modelo nunca ve un stack trace. Recibe un rechazo estructurado que puede explicarle al usuario,
y tú obtienes un recibo que puedes verificar después.

## Inicio rápido

> **Pre-alpha:** esta es la API que estamos construyendo para la `0.1.0`. Dale *Watch* al repo para enterarte cuando salga.

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
      tools: ["db.*"]
      when: "#args['query'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"
      decision: deny
    - id: transfers-need-approval
      tools: ["payments.transfer"]
      when: "#args['amount'] > 1000"
      decision: require-approval
```

**3. Listo.** Tus herramientas existentes se envuelven automáticamente:

```java
@Tool(description = "Transfer money between accounts")
@RequiresApproval(reason = "Moves real money")   // opcional: política por herramienta como anotación
public TransferResult transfer(String from, String to, BigDecimal amount) { ... }
```

Al arrancar verás una línea en el log que indica que Countersign está activo y qué decisión usa por defecto.

## Qué pasa en una llamada a herramienta

<p align="center">
  <img src="docs/images/tool-call-flow.drawio.svg" width="860"
       alt="Flujo de una llamada: el agente propone la llamada, GuardedToolCallback consulta al PolicyEngine, que devuelve ALLOW, REDACT, REQUIRE APPROVAL o DENY. Las llamadas permitidas, redactadas o aprobadas ejecutan la herramienta; las denegadas, rechazadas o vencidas devuelven un rechazo estructurado. Todos los caminos generan un recibo firmado que va al AuditSink y a Micrometer.">
</p>

1. **Construye** un `ToolInvocation`: nombre de la herramienta, argumentos parseados, principal e id de conversación.
2. **Evalúa** la invocación contra tus políticas, en orden de prioridad. Gana la primera que coincide; si ninguna coincide, se aplica la decisión por defecto (`deny`).
3. **Aplica** la decisión: ejecuta la herramienta, la rechaza, le pregunta a un humano o redacta los datos.
4. **Registra** un recibo: la decisión, las políticas que coincidieron, un SHA-256 de los argumentos (nunca los valores en crudo), el hash del recibo anterior y una firma Ed25519.
5. **Publica** el recibo en tu `AuditSink`, junto con métricas y observaciones de Micrometer.

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

- **Cuatro decisiones:** `ALLOW`, `DENY`, `REQUIRE_APPROVAL`, `REDACT`.
- **Políticas declarativas:** globs de herramientas (`db.*`), prioridades y condiciones escritas en un
  SpEL aislado (`SimpleEvaluationContext`: sin referencias a tipos, sin acceso a beans, sin llamadas a métodos arbitrarios).
- **Anotaciones:** `@RequiresApproval` y `@ToolPolicy(id = "...")` en tus métodos `@Tool`.
- **Humano en el ciclo:** un `ApprovalGateway` intercambiable. El que viene por defecto deniega cuando la aprobación vence.
- **Recibos a prueba de manipulación:** JSON canónico, cadena de hashes SHA-256 y firmas Ed25519 usando solo
  `java.security`, más una utilidad de verificación que detecta cualquier recibo editado o borrado.
- **Privacidad por defecto:** los recibos guardan un hash de los argumentos. Guardar los valores en crudo es opcional y pasa por un `Redactor`.
- **Destinos de auditoría:** en memoria y PostgreSQL (solo inserción) incluidos.
- **Observabilidad:** `countersign.decisions`, `countersign.evaluation` y `countersign.audit.failures` vía Micrometer.
- **Utilidades de test:** `countersign-test` te permite comprobar en tus propios tests que tu agente fue bloqueado.

### Un recibo

```json
{
  "invocationId": "01JD6Y3Q8M7K4T2V9X0B5N1C3R",
  "tool": "payments.transfer",
  "argsSha256": "9b2f4c…e71a",
  "decision": "REQUIRE_APPROVAL",
  "matchedPolicies": ["transfers-need-approval"],
  "timestamp": "2026-11-18T14:02:11.482Z",
  "previousHash": "1b42…08aa",
  "hash": "7f3a…c91e",
  "signature": "MEUCIQ…"
}
```

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

**Requisitos:** Java 21, Spring Boot 4.x, Spring AI 2.0.1 o superior.

## Hoja de ruta hacia la 0.1.0

- [x] `countersign-core`: modelo, `PolicyEngine`, condiciones SpEL aisladas
- [ ] Recibos: JSON canónico, cadena SHA-256, firma Ed25519, verificación
- [ ] `GuardedToolCallback` y envoltura automática de métodos `@Tool`
- [ ] `ALLOW` / `DENY` / `REQUIRE_APPROVAL` con un gateway que deniega al vencer el tiempo
- [ ] `@RequiresApproval`
- [ ] Starter de Spring Boot y línea de arranque en el log
- [ ] Destinos de auditoría en memoria y PostgreSQL
- [ ] Métricas y observaciones de Micrometer
- [ ] Ejemplo `banking-agent` y GIF de demo
- [ ] Publicado en Maven Central

No está previsto para la `0.1.0`: clasificadores basados en LLM, un panel web, frameworks que no sean Spring, A2A ni un servidor remoto de políticas.

## Contribuir

Countersign está pensado para crecer a través de sus SPIs. Puedes hacer una contribución útil sin
tocar el motor de políticas:

- **`ApprovalGateway`:** aprobaciones por Slack, Teams, correo o una consola web
- **`AuditSink`:** Kafka, S3, OpenSearch o tu SIEM
- **`Redactor`:** estrategias de enmascaramiento de datos personales
- **`ReceiptSigner`:** firma respaldada por KMS / HSM

Cada SPI trae un test de contrato abstracto en `countersign-test`. Pasarlo es el requisito para hacer merge.
La arquitectura, las convenciones y las reglas de testing están en [`CLAUDE.md`](CLAUDE.md) (en inglés).
Los issues con la etiqueta `good first issue` son el mejor punto de partida. El código, los comentarios
y los commits se escriben en inglés, pero puedes abrir issues y discusiones en español.

Si Countersign te parece útil, una ⭐ ayuda a que otros equipos de Spring lo encuentren.

## Licencia

Apache License 2.0. Consulta [`LICENSE`](LICENSE).
