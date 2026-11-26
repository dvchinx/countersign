package io.github.dvchinx.countersign.audit.jdbc;

import static io.github.dvchinx.countersign.test.TestReceipts.allow;
import static io.github.dvchinx.countersign.test.TestReceipts.invocation;
import static io.github.dvchinx.countersign.test.TestReceipts.newChain;
import static io.github.dvchinx.countersign.test.TestReceipts.verifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.VerificationResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcAuditSinkTest {

    private DataSource dataSource;
    private JdbcTemplate sql;
    private JdbcAuditSink sink;

    @BeforeEach
    void freshDatabase() {
        dataSource = TestDatabase.freshSchema();
        JdbcAuditSink.createSchema(dataSource);
        sql = new JdbcTemplate(dataSource);
        sink = new JdbcAuditSink(dataSource);
    }

    private List<Receipt> appendThree(ReceiptChain chain) {
        List<Receipt> receipts = IntStream.range(0, 3)
                .mapToObj(i -> chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")))
                .toList();
        receipts.forEach(sink::append);
        return receipts;
    }

    /** Runs a change as the table owner with the append-only triggers off, as an attacker with DBA rights could. */
    private void tamper(String statement, Object... args) {
        sql.execute("ALTER TABLE countersign_receipt DISABLE TRIGGER USER");
        try {
            sql.update(statement, args);
        } finally {
            sql.execute("ALTER TABLE countersign_receipt ENABLE TRIGGER USER");
        }
    }

    @Test
    void rejectsUpdatesDeletesAndTruncation() {
        ReceiptChain chain = newChain();
        appendThree(chain);

        assertThatThrownBy(() -> sql.update("UPDATE countersign_receipt SET reason = 'edited'"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only: UPDATE");
        assertThatThrownBy(() -> sql.update("DELETE FROM countersign_receipt"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only: DELETE");
        assertThatThrownBy(() -> sql.execute("TRUNCATE countersign_receipt"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only: TRUNCATE");

        assertThat(verifier().verify(sink.stream(chain.streamId()))).isEqualTo(new VerificationResult.Valid(3));
    }

    @Test
    void rejectsASecondReceiptWithTheSameSequence() {
        ReceiptChain chain = newChain();
        Receipt receipt = chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));
        sink.append(receipt);

        assertThatThrownBy(() -> sink.append(receipt)).isInstanceOf(DuplicateKeyException.class);
        assertThat(sink.stream(chain.streamId())).containsExactly(receipt);
    }

    @Test
    void detectsARowEditedBehindTheTriggers() {
        ReceiptChain chain = newChain();
        appendThree(chain);

        tamper(
                "UPDATE countersign_receipt SET decision = 'DENY' WHERE stream_id = ? AND sequence_number = 2",
                chain.streamId());

        assertThat(verifier().verify(sink.stream(chain.streamId())))
                .isEqualTo(new VerificationResult.Invalid(1, 2, VerificationResult.Problem.HASH_MISMATCH));
    }

    @Test
    void detectsARowDeletedBehindTheTriggers() {
        ReceiptChain chain = newChain();
        appendThree(chain);

        tamper("DELETE FROM countersign_receipt WHERE stream_id = ? AND sequence_number = 2", chain.streamId());

        assertThat(verifier().verify(sink.stream(chain.streamId())))
                .isEqualTo(new VerificationResult.Invalid(1, 3, VerificationResult.Problem.SEQUENCE_GAP));
    }

    @Test
    void listsStreamsOldestFirst() {
        ReceiptChain first = newChain();
        ReceiptChain second = newChain();
        sink.append(first.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")));
        sink.append(second.append(invocation("accounts.balance", "bob", "c2"), allow("read-only")));
        sink.append(first.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")));

        assertThat(sink.streamIds()).containsExactly(first.streamId(), second.streamId());
    }

    @Test
    void createsTheSchemaOnceWhenManyInstancesStartTogether() throws Exception {
        DataSource empty = TestDatabase.freshSchema();
        Callable<Void> create = () -> {
            JdbcAuditSink.createSchema(empty);
            return null;
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (Future<Void> started : executor.invokeAll(List.of(create, create, create, create, create, create))) {
                started.get();
            }
        }
        JdbcAuditSink.createSchema(empty);

        JdbcTemplate emptySql = new JdbcTemplate(empty);
        assertThat(emptySql.queryForObject(
                        "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'countersign_receipt'::regclass"
                                + " AND NOT tgisinternal",
                        Integer.class))
                .isEqualTo(2);
        Receipt receipt = newChain().append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));
        new JdbcAuditSink(empty).append(receipt);
        assertThat(new JdbcAuditSink(empty).stream(receipt.streamId())).containsExactly(receipt);
    }

    @Test
    void refusesToReadARowThatIsNotAValidReceipt() {
        ReceiptChain chain = newChain();
        appendThree(chain);
        tamper(
                "UPDATE countersign_receipt SET basis = 'MADE_UP' WHERE stream_id = ? AND sequence_number = 3",
                chain.streamId());

        assertThatThrownBy(() -> sink.stream(chain.streamId()))
                .isInstanceOf(JdbcAuditException.class)
                .hasMessage("Stored receipt " + chain.streamId() + "#3 is not a valid receipt");
    }

    @Test
    void throwsWhenTheDatabaseIsDown() {
        JdbcAuditSink down = new JdbcAuditSink(TestDatabase.unreachable());
        Receipt receipt = newChain().append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));

        assertThatThrownBy(() -> down.append(receipt)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void refusesAnAllowedCallWhenTheDatabaseIsDownAndAuditIsStrict() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("read-only")
                        .tools("accounts.balance")
                        .decision(Decision.allow())
                        .build()),
                Decision.deny("No policy allows this call"));
        ToolCallGuard guard = ToolCallGuard.builder(engine, newChain(), new JdbcAuditSink(TestDatabase.unreachable()))
                .strictAudit(true)
                .build();

        Verdict verdict = guard.check(ToolInvocation.of(
                "accounts.balance", JsonObject.builder().put("account", "A-1").build()));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(refuse.reason())
                    .isEqualTo("The call was blocked because Countersign could not store its record");
        });
    }

    @Test
    void storesTheReceiptOfARealDecision() {
        PolicyEngine engine = new PolicyEngine(List.of(), Decision.deny("No policy allows this call"));
        ReceiptChain chain = newChain();
        ToolCallGuard guard =
                ToolCallGuard.builder(engine, chain, sink).strictAudit(true).build();

        Verdict verdict = guard.check(ToolInvocation.of(
                "db.query", JsonObject.builder().put("q", "DROP TABLE x").build()));

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Refuse.class,
                        refuse -> assertThat(sink.stream(chain.streamId()))
                                .singleElement()
                                .satisfies(receipt -> {
                                    assertThat(receipt.decision()).isEqualTo(Decision.Type.DENY);
                                    assertThat(receipt.basis()).isEqualTo(EvaluationResult.Basis.DEFAULT_DECISION);
                                    assertThat(receipt).isEqualTo(refuse.receipt());
                                }));
    }

    @Test
    void upgradesATableCreatedBy010AndKeepsItsReceiptsVerifiable() throws Exception {
        DataSource old = TestDatabase.freshSchema();
        JdbcTemplate oldSql = new JdbcTemplate(old);
        try (InputStream script = getClass().getClassLoader().getResourceAsStream("schema-postgresql-0.1.0.sql")) {
            oldSql.execute(new String(script.readAllBytes(), StandardCharsets.UTF_8));
        }
        ReceiptChain chain = newChain();
        Receipt before = chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));
        // Written the way 0.1.0 wrote it, with no redactions column.
        oldSql.update(
                "INSERT INTO countersign_receipt (stream_id, sequence_number, invocation_id, tool, args_hmac, decision,"
                        + " reason, basis, matched_policies, approval, approver, principal, conversation_id,"
                        + " created_at, previous_hash, hash, signature)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?)",
                before.streamId(),
                before.sequence(),
                before.invocationId(),
                before.tool(),
                before.argsHmac(),
                before.decision().name(),
                before.reason(),
                before.basis().name(),
                "{read-only}",
                null,
                null,
                before.principal(),
                before.conversationId(),
                OffsetDateTime.ofInstant(before.timestamp(), ZoneOffset.UTC),
                before.previousHash(),
                before.hash(),
                before.signature());

        JdbcAuditSink.createSchema(old);
        JdbcAuditSink upgraded = new JdbcAuditSink(old);
        Receipt after = chain.append(
                invocation("crm_find_customer", "alice", "c1"), allow("crm-reads"), null, null, List.of("no-cards"));
        upgraded.append(after);

        List<Receipt> stored = upgraded.stream(chain.streamId());
        assertThat(stored).containsExactly(before, after);
        assertThat(stored.getLast().redactions()).containsExactly("no-cards");
        assertThat(verifier().verify(stored)).isEqualTo(new VerificationResult.Valid(2));
    }

    @Test
    void storesTheRedactionsOfAReceipt() {
        ReceiptChain chain = newChain();
        Receipt receipt = chain.append(
                invocation("crm_find_customer", "alice", "c1"),
                allow("crm-reads"),
                null,
                null,
                List.of("no-cards", "no-contact"));

        sink.append(receipt);

        assertThat(sql.queryForObject(
                        "SELECT array_to_string(redactions, ',') FROM countersign_receipt WHERE stream_id = ?",
                        String.class,
                        chain.streamId()))
                .isEqualTo("no-cards,no-contact");
        assertThat(sink.stream(chain.streamId())).containsExactly(receipt);
    }
}
