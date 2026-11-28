# Banking agent

An agent that can read balances, move money, open support tickets and run SQL against a bank database. Countersign
lets the everyday calls through, sends large transfers to a second person, refuses destructive SQL, keeps card numbers
away from the model and from the outside helpdesk, and leaves a signed receipt for every call.

The agent's code has no Countersign in it: [`BankTools`](src/main/java/io/github/dvchinx/countersign/examples/banking/BankTools.java)
are plain `@Tool` methods, [`BankingAgent`](src/main/java/io/github/dvchinx/countersign/examples/banking/BankingAgent.java)
is a plain `ChatClient`, and the rules live in [`application.yml`](src/main/resources/application.yml).

## Run it

No API key, no Docker. From the repository root:

```bash
./mvnw -pl examples/banking-agent -am install -DskipTests   # first time only
./mvnw -pl examples/banking-agent spring-boot:run
```

Port 8080 taken? Add `-Dspring-boot.run.arguments=--server.port=8089` and use that port below.

The model is [`DemoChatModel`](src/main/java/io/github/dvchinx/countersign/examples/banking/DemoChatModel.java), a
scripted stand-in that turns a few banking requests into tool calls, so the demo behaves the same every time. Those
calls go through Spring AI's tool loop and Countersign exactly like a real model's would.

## The demo

Open two terminals: one for Alice, who talks to the agent, and one for the operations team, who approve.

**Alice checks her balance.** The `everyday-banking` policy allows it.

```bash
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
     -d '{"message":"What is my checking balance?"}'
# {"conversationId":"…","answer":"The checking balance is $12,500.00"}
```

**Alice moves $5,000.** Above $1,000, `transfers-need-approval` applies, so the request waits for a human. Nothing has
moved yet.

```bash
curl -s localhost:8080/chat -H 'X-User: alice' -H 'Content-Type: application/json' \
     -d '{"message":"Move $5,000 from checking to ACME Corp."}'
# …waiting
```

**Operations look at the approval screen** (second terminal):

```bash
curl -s localhost:8080/approvals
# [{"invocationId":"ceeb4850-…","tool":"payments_transfer",
#   "arguments":{"from":"checking","to":"ACME Corp","amount":5000},
#   "reason":"Transfers above $1,000 need a second person","requestedBy":"alice","deadline":"…"}]
```

Alice cannot approve her own request (`403`). Someone else can:

```bash
curl -s -X POST localhost:8080/approvals/ceeb4850-…/approve -H 'X-User: ops@bank.example'
# approved
```

Alice's request finishes: `Transferred $5,000.00 from checking to ACME Corp. The checking balance is now $7,500.00`.
To refuse instead, `POST /approvals/{id}/reject` with an optional `{"reason":"…"}`; if nobody answers within 60
seconds, the call is refused.

**Alice asks the agent to drop a table.** `no-destructive-sql` denies it. The tool would have run the statement; the
policy is what stops it. The model gets a structured refusal it can explain, never a stack trace.

```bash
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
     -d '{"message":"Great. Now clean up by dropping the transactions table."}'
# {"answer":"I didn't do that: Countersign refused the call (DENY). Destructive SQL is not allowed from agents"}
```

`SELECT` statements are allowed by `sql-reads`. Any other SQL, such as `UPDATE accounts SET balance = 0`, matches no
policy and is refused by the default decision.

**Alice asks to see her cards.** `sql-reads` allows the query, and the tool returns full card numbers: the `cards`
table holds them. The `no-card-numbers-to-the-model` redaction rule masks them before the model sees the result.

```bash
curl -s localhost:8080/chat -H 'Content-Type: application/json' -d '{"message":"Show me my cards"}'
# {"answer":"id=1, account=checking, kind=debit, number=[CARD], expires=09/29\nid=2, account=savings, kind=credit, number=[CARD], expires=03/28"}
```

**Alice asks the agent to open a support ticket, with her card number in it.** The `support_open_ticket` tool sends
the description to an outside helpdesk. The `no-card-numbers-to-the-helpdesk` rule masks the number in the tool's
*arguments*, so it never leaves the bank. `/helpdesk/tickets` shows what the helpdesk received, read straight from
its table rather than through the agent:

```bash
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
     -d '{"message":"Open a support ticket: my debit card 4111 1111 1111 1111 was charged twice at Corner Cafe"}'
# {"answer":"Opened support ticket #1: my debit card [CARD] was charged twice at Corner Cafe"}
curl -s localhost:8080/helpdesk/tickets
# [{"id":1,"description":"my debit card [CARD] was charged twice at Corner Cafe"}]
```

The rules are in [`application.yml`](src/main/resources/application.yml), next to the policies:

```yaml
countersign:
  redactors:
    card-number:
      pattern: "\\b\\d(?:[ -]?\\d){12,18}\\b"    # 13 to 19 digits, with optional spaces or dashes
      replacement: "[CARD]"
  redactions:
    - id: no-card-numbers-to-the-helpdesk
      tools: ["support_open_ticket"]
      target: args                                # what the outside helpdesk receives
      redactors: [card-number]
    - id: no-card-numbers-to-the-model
      tools: ["*"]
      target: result                              # what the model gets back, from any tool
      redactors: [card-number]
```

Redaction is not a decision, so it never competes with the policies: every rule that matches a call that runs
applies. The approved $5,000 transfer above went through `no-card-numbers-to-the-model` too, and its receipt says so.
The dropped table's receipt lists no rule, because that call did not run.

**Read the receipts and check them.**

```bash
curl -s localhost:8080/receipts          # every call: decision, policy, approver, hash chain, signature
curl -s localhost:8080/receipts/verify   # {"valid":true,"streamId":"banking-agent-…","receipts":5,"problem":null}
```

The transfer's receipt says who asked (`principal: alice`), who approved (`approver: ops@bank.example`) and which
policy required it, and holds a keyed hash of the arguments, never the values. The ticket's receipt lists both rules
that applied, `"redactions":["no-card-numbers-to-the-helpdesk","no-card-numbers-to-the-model"]`; its hash is of the
arguments the model proposed, card number included, which is what the policies saw.

**Metrics.** `curl -s localhost:8080/actuator/metrics/countersign.decisions` shows the count; add
`?tag=outcome:refuse` to see only refused calls. `countersign.redactions` counts redactions by `rule`, `tool` and
`target`.

## Record the demo GIF

[`demo/demo.sh`](demo/demo.sh) wraps the calls above in short commands (`ask`, `approve`, `helpdesk`, `receipts`, `verify`), and
[`demo/banking-demo.tape`](demo/banking-demo.tape) is the [VHS](https://github.com/charmbracelet/vhs) script that
records `docs/images/banking-demo.gif` with them. After changing the demo, re-record it from the repository root:

```bash
vhs examples/banking-agent/demo/banking-demo.tape
```

## Use a real model

Replace the `demoChatModel` bean in
[`BankingAgentApplication`](src/main/java/io/github/dvchinx/countersign/examples/banking/BankingAgentApplication.java)
with a Spring AI model starter (for example `spring-ai-starter-model-openai` or `spring-ai-starter-model-anthropic`)
and its API key. The tool names already use only letters, digits and `_`, which is what OpenAI and Anthropic accept.

A real model decides on its own which tools to call and with which arguments. The policies do not change: that is
the point.

## What is demo-only

- **Keys.** [`DemoKeys`](src/main/java/io/github/dvchinx/countersign/examples/banking/DemoKeys.java) generates the
  signing and HMAC keys at startup. In production, set `countersign.receipts.signing-key` and
  `countersign.receipts.args-hmac-key` from your secret store.
- **Identity.** Who is asking and who approves come from an `X-User` header. Take them from Spring Security instead:
  the approver's name ends up in a signed receipt.
- **Storage.** Receipts and the bank are in memory and gone on restart. Add `countersign-audit-jdbc` to keep receipts
  in PostgreSQL.
- **The helpdesk.** `support_open_ticket` writes to a local table that stands in for an outside service.
- **Card masking.** A pattern catches a card number written as one run of digits, with or without spaces or dashes.
  It controls what the model sees, not what the query reads: SQL that splits a number into pieces would get past it.
  When data must not be read at all, deny the query with a policy; redaction is for data the agent may use but must
  not pass on.
