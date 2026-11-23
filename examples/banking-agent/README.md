# Banking agent

An agent that can read balances, move money and run SQL against a bank database. Countersign lets the everyday
calls through, sends large transfers to a second person, refuses destructive SQL, and leaves a signed receipt for
every call.

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
# [{"invocationId":"ceeb4850-…","tool":"payments.transfer",
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

**Read the receipts and check them.**

```bash
curl -s localhost:8080/receipts          # every call: decision, policy, approver, hash chain, signature
curl -s localhost:8080/receipts/verify   # {"valid":true,"streamId":"banking-agent-…","receipts":3,"problem":null}
```

The transfer's receipt says who asked (`principal: alice`), who approved (`approver: ops@bank.example`) and which
policy required it, and holds a keyed hash of the arguments, never the values.

**Metrics.** `curl -s localhost:8080/actuator/metrics/countersign.decisions` shows the count; add
`?tag=outcome:refuse` to see only refused calls.

## Use a real model

Replace the `demoChatModel` bean in
[`BankingAgentApplication`](src/main/java/io/github/dvchinx/countersign/examples/banking/BankingAgentApplication.java)
with a Spring AI model starter (for example `spring-ai-starter-model-openai` or `spring-ai-starter-model-anthropic`)
and its API key. Two things to know:

- OpenAI and Anthropic only accept tool names made of letters, digits, `_` and `-`. Rename the tools
  (`payments_transfer`, `accounts_balance`, `db_query`) and the `tools:` globs in `application.yml` to match.
- A real model decides on its own which tools to call and with which arguments. The policies do not change: that is
  the point.

## What is demo-only

- **Keys.** [`DemoKeys`](src/main/java/io/github/dvchinx/countersign/examples/banking/DemoKeys.java) generates the
  signing and HMAC keys at startup. In production, set `countersign.receipts.signing-key` and
  `countersign.receipts.args-hmac-key` from your secret store.
- **Identity.** Who is asking and who approves come from an `X-User` header. Take them from Spring Security instead:
  the approver's name ends up in a signed receipt.
- **Storage.** Receipts and the bank are in memory and gone on restart. Add `countersign-audit-jdbc` to keep receipts
  in PostgreSQL.
