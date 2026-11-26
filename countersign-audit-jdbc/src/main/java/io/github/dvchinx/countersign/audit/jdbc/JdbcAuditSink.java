package io.github.dvchinx.countersign.audit.jdbc;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores receipts in a PostgreSQL table, {@code countersign_receipt}, that only accepts inserts.
 *
 * <p>Each receipt is one row, keyed on its stream id and sequence, with every field stored exactly as it was hashed
 * and signed. {@link #stream(String)} reads a stream back in sequence order, so it can be checked with
 * {@code ReceiptVerifier} straight away. Triggers reject {@code UPDATE}, {@code DELETE} and {@code TRUNCATE}; the
 * signatures and the hash chain detect changes made by anyone who gets past them.
 *
 * <p>Writes are synchronous: {@link #append} returns once the row is committed, or throws if it was not. That lets
 * {@code countersign.audit.strict=true} refuse a call whose receipt was not stored. Storing the same receipt twice
 * fails on the primary key, so a stream cannot hold two receipts with the same sequence.
 *
 * <p>Create the table with {@link #createSchema(DataSource)}, or run {@link #SCHEMA_RESOURCE} with your own
 * migration tool. A table created by 0.1.0 lacks the {@code redactions} column (ADR 0015): {@code createSchema} adds
 * it, and a migration tool must run {@link #ADD_REDACTIONS_COLUMN}. Requires PostgreSQL 14 or later. Thread-safe.
 */
public final class JdbcAuditSink implements AuditSink {

    /** The classpath location of the schema script, for migration tools. */
    public static final String SCHEMA_RESOURCE = "io/github/dvchinx/countersign/audit/jdbc/schema-postgresql.sql";

    /** The table receipts are stored in. */
    public static final String TABLE = "countersign_receipt";

    /**
     * The statement that brings a table created by 0.1.0 up to date, for applications that manage the schema
     * themselves. Run it before upgrading: without the column, every insert fails.
     */
    public static final String ADD_REDACTIONS_COLUMN =
            "ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS redactions text[] NOT NULL DEFAULT '{}'";

    // Any constant works; it only has to be the same for every instance creating the schema at once.
    private static final long SCHEMA_LOCK_KEY = 0x436f_756e_7465_7273L;

    private static final String COLUMNS =
            "stream_id, sequence_number, invocation_id, tool, args_hmac, decision, reason,"
                    + " basis, matched_policies, approval, approver, principal, conversation_id, created_at, previous_hash,"
                    + " hash, signature, redactions";

    private static final String INSERT =
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_STREAM =
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE stream_id = ? ORDER BY sequence_number";

    private static final String SELECT_STREAM_IDS =
            "SELECT stream_id FROM " + TABLE + " GROUP BY stream_id ORDER BY min(created_at), stream_id";

    private final JdbcTemplate jdbc;

    /**
     * Creates a sink. The table must already exist; see {@link #createSchema(DataSource)}.
     *
     * @param dataSource a PostgreSQL data source
     */
    public JdbcAuditSink(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * Creates the receipts table and its append-only triggers, unless the table already exists, and adds any column a
     * newer version needs to a table that lacks it.
     *
     * <p>Safe to call from several instances starting at the same time: they take turns on a transaction-scoped
     * advisory lock. When the table is up to date nothing is run, so an application role with only {@code INSERT}
     * and {@code SELECT} rights can call it once the table has been created and migrated.
     *
     * @param dataSource a PostgreSQL data source whose user may create tables, the first time
     * @throws org.springframework.dao.DataAccessException if the script fails
     */
    public static void createSchema(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String script = schemaScript();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, SCHEMA_LOCK_KEY);
            Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, TABLE);
            if (!Boolean.TRUE.equals(exists)) {
                jdbc.execute(script);
            } else if (!hasColumn(jdbc, "redactions")) {
                jdbc.execute(ADD_REDACTIONS_COLUMN);
            }
        });
    }

    // Checked before altering, because ALTER TABLE needs the table's owner even when the column already exists.
    private static boolean hasColumn(JdbcTemplate jdbc, String column) {
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ?"
                        + " AND NOT attisdropped)",
                Boolean.class,
                TABLE,
                column);
        return Boolean.TRUE.equals(present);
    }

    /**
     * Stores one receipt.
     *
     * @param receipt the receipt, already signed and chained
     * @throws org.springframework.dao.DuplicateKeyException if the stream already has a receipt with this sequence
     * @throws org.springframework.dao.DataAccessException if the receipt could not be stored
     */
    @Override
    public void append(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        jdbc.update(INSERT, ps -> {
            Array policies = ps.getConnection()
                    .createArrayOf("text", receipt.matchedPolicies().toArray(String[]::new));
            Array redactions = ps.getConnection()
                    .createArrayOf("text", receipt.redactions().toArray(String[]::new));
            try {
                ps.setString(1, receipt.streamId());
                ps.setLong(2, receipt.sequence());
                ps.setString(3, receipt.invocationId());
                ps.setString(4, receipt.tool());
                ps.setString(5, receipt.argsHmac());
                ps.setString(6, receipt.decision().name());
                ps.setString(7, receipt.reason());
                ps.setString(8, receipt.basis().name());
                ps.setArray(9, policies);
                ps.setString(
                        10,
                        receipt.approval() == null ? null : receipt.approval().name());
                ps.setString(11, receipt.approver());
                ps.setString(12, receipt.principal());
                ps.setString(13, receipt.conversationId());
                ps.setObject(14, OffsetDateTime.ofInstant(receipt.timestamp(), ZoneOffset.UTC));
                ps.setString(15, receipt.previousHash());
                ps.setString(16, receipt.hash());
                ps.setString(17, receipt.signature());
                ps.setArray(18, redactions);
            } finally {
                policies.free();
                redactions.free();
            }
        });
    }

    /**
     * Reads one stream, in sequence order, ready for {@code ReceiptVerifier}.
     *
     * <p>The whole stream is loaded into memory. One stream is one application run, so its size is bounded by what one
     * instance records before it restarts.
     *
     * @param streamId the stream
     * @return the stream's receipts; empty if none are stored
     * @throws JdbcAuditException if a stored row is not a valid receipt
     */
    public List<Receipt> stream(String streamId) {
        Objects.requireNonNull(streamId, "streamId");
        return jdbc.query(SELECT_STREAM, RECEIPT, streamId);
    }

    /**
     * Lists the stored streams, oldest first.
     *
     * @return the stream ids
     */
    public List<String> streamIds() {
        return jdbc.queryForList(SELECT_STREAM_IDS, String.class);
    }

    private static final RowMapper<Receipt> RECEIPT = (rs, row) -> {
        try {
            return new Receipt(
                    rs.getString("stream_id"),
                    rs.getLong("sequence_number"),
                    rs.getString("invocation_id"),
                    rs.getString("tool"),
                    rs.getString("args_hmac"),
                    Decision.Type.valueOf(rs.getString("decision")),
                    rs.getString("reason"),
                    EvaluationResult.Basis.valueOf(rs.getString("basis")),
                    strings(rs, "matched_policies"),
                    strings(rs, "redactions"),
                    approval(rs.getString("approval")),
                    rs.getString("approver"),
                    rs.getString("principal"),
                    rs.getString("conversation_id"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getString("previous_hash"),
                    rs.getString("hash"),
                    rs.getString("signature"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JdbcAuditException(
                    "Stored receipt " + rs.getString("stream_id") + "#" + rs.getLong("sequence_number")
                            + " is not a valid receipt",
                    e);
        }
    };

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static @Nullable ApprovalOutcome approval(@Nullable String name) {
        return name == null ? null : ApprovalOutcome.valueOf(name);
    }

    private static String schemaScript() {
        try (InputStream in = JdbcAuditSink.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new JdbcAuditException("Schema script " + SCHEMA_RESOURCE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JdbcAuditException("Could not read schema script " + SCHEMA_RESOURCE, e);
        }
    }
}
