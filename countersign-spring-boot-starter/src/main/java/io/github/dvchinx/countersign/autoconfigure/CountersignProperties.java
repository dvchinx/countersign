package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Redaction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code countersign.*} configuration properties. Part of the public API: renaming or removing a property is a
 * breaking change.
 */
@ConfigurationProperties("countersign")
public class CountersignProperties {

    /** Whether Countersign guards tool calls. When false, tools run unchecked and a warning is logged at startup. */
    private boolean enabled = true;

    /**
     * Decision for a call that no policy matches. Keep "deny" to fail closed; any other value is logged as a warning
     * at startup.
     */
    private Decision.Type defaultDecision = Decision.Type.DENY;

    /** Reason given to the model when the default decision refuses a call. */
    private String defaultReason = "No policy allows this call";

    /** Policies, evaluated by ascending priority, then in the order listed. The first one that applies decides. */
    private List<PolicyProperties> policies = new ArrayList<>();

    /**
     * Named redactors that redaction rules refer to. Each has either a regular expression ('pattern') or a list of
     * key names ('fields'). Redactor beans of the application are also available, under their bean names.
     */
    private Map<String, RedactorProperties> redactors = new LinkedHashMap<>();

    /**
     * Redaction rules. Unlike policies, every rule that matches a call that runs applies to it, whatever decided
     * access, in the order listed (ADR 0015).
     */
    private List<RedactionProperties> redactions = new ArrayList<>();

    /**
     * Extra packages scanned for @RequiresApproval tool methods. The application's own packages (those of its
     * @SpringBootApplication class) are always scanned.
     */
    private List<String> annotationPackages = new ArrayList<>();

    /** Receipt signing and chaining. */
    private Receipts receipts = new Receipts();

    /** Where receipts are stored, and what happens when storing one fails. */
    private Audit audit = new Audit();

    /** Human approval of calls that a policy marks require-approval. */
    private Approval approval = new Approval();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Decision.Type getDefaultDecision() {
        return defaultDecision;
    }

    public void setDefaultDecision(Decision.Type defaultDecision) {
        this.defaultDecision = defaultDecision;
    }

    public String getDefaultReason() {
        return defaultReason;
    }

    public void setDefaultReason(String defaultReason) {
        this.defaultReason = defaultReason;
    }

    public List<PolicyProperties> getPolicies() {
        return policies;
    }

    public void setPolicies(List<PolicyProperties> policies) {
        this.policies = policies;
    }

    public Map<String, RedactorProperties> getRedactors() {
        return redactors;
    }

    public void setRedactors(Map<String, RedactorProperties> redactors) {
        this.redactors = redactors;
    }

    public List<RedactionProperties> getRedactions() {
        return redactions;
    }

    public void setRedactions(List<RedactionProperties> redactions) {
        this.redactions = redactions;
    }

    public List<String> getAnnotationPackages() {
        return annotationPackages;
    }

    public void setAnnotationPackages(List<String> annotationPackages) {
        this.annotationPackages = annotationPackages;
    }

    public Receipts getReceipts() {
        return receipts;
    }

    public void setReceipts(Receipts receipts) {
        this.receipts = receipts;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Approval getApproval() {
        return approval;
    }

    public void setApproval(Approval approval) {
        this.approval = approval;
    }

    /** Approval settings. The approver itself is an ApprovalGateway bean. */
    public static class Approval {

        /**
         * How long a call waits for a human answer before it is refused. The agent's request blocks for up to this
         * long, so keep it below any HTTP timeout in front of it.
         */
        private Duration timeout = Duration.ofMinutes(2);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** One policy, as written under {@code countersign.policies}. */
    public static class PolicyProperties {

        /** Unique id, recorded in receipts and metrics. Letters, digits, '.', '_' and '-'. */
        private @Nullable String id;

        /** Tool name globs this policy applies to, such as {@code db_*}. At least one. */
        private List<String> tools = new ArrayList<>();

        /**
         * Optional SpEL condition, such as {@code #args['amount'] > 1000}. Evaluated in a read-only sandbox; the
         * variables are {@code #args}, {@code #tool}, {@code #principal} and {@code #conversationId}.
         */
        private @Nullable String when;

        /** What to do with a call this policy applies to: allow, deny or require-approval. */
        private Decision.@Nullable Type decision;

        /** Reason shown to the model (deny) or the approver (require-approval). Defaults to a generic text. */
        private @Nullable String reason;

        /** Evaluation order; lower runs first. */
        private int priority = 0;

        public @Nullable String getId() {
            return id;
        }

        public void setId(@Nullable String id) {
            this.id = id;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public @Nullable String getWhen() {
            return when;
        }

        public void setWhen(@Nullable String when) {
            this.when = when;
        }

        public Decision.@Nullable Type getDecision() {
            return decision;
        }

        public void setDecision(Decision.@Nullable Type decision) {
            this.decision = decision;
        }

        public @Nullable String getReason() {
            return reason;
        }

        public void setReason(@Nullable String reason) {
            this.reason = reason;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }

    /** One redactor, as written under {@code countersign.redactors.<name>}. Set either 'pattern' or 'fields'. */
    public static class RedactorProperties {

        /**
         * Regular expression (java.util.regex) whose matches are replaced in every string value of the result, and
         * in every number whose digits match. Keep it simple: it runs on data the model controls.
         */
        private @Nullable String pattern;

        /** Key names whose values are replaced, at any depth, ignoring case. */
        private List<String> fields = new ArrayList<>();

        /** Literal text that replaces each match or value. */
        private String replacement = "[REDACTED]";

        public @Nullable String getPattern() {
            return pattern;
        }

        public void setPattern(@Nullable String pattern) {
            this.pattern = pattern;
        }

        public List<String> getFields() {
            return fields;
        }

        public void setFields(List<String> fields) {
            this.fields = fields;
        }

        public String getReplacement() {
            return replacement;
        }

        public void setReplacement(String replacement) {
            this.replacement = replacement;
        }
    }

    /** One redaction rule, as written under {@code countersign.redactions}. */
    public static class RedactionProperties {

        /** Unique id, shared with policy ids and recorded in receipts. Letters, digits, '.', '_' and '-'. */
        private @Nullable String id;

        /** Tool name globs this rule applies to, such as {@code crm_*}. At least one. */
        private List<String> tools = new ArrayList<>();

        /**
         * Optional SpEL condition over the arguments the model proposed, with the same sandbox and variables as a
         * policy condition.
         */
        private @Nullable String when;

        /**
         * What to redact: 'args' (what the tool receives; the receipt keeps the hash of what the model proposed),
         * 'result' (what the model gets back), or both ('args, result').
         */
        private Set<Redaction.Target> target = EnumSet.noneOf(Redaction.Target.class);

        /** Names of the redactors to apply, in order: keys of countersign.redactors, or Redactor bean names. */
        private List<String> redactors = new ArrayList<>();

        public @Nullable String getId() {
            return id;
        }

        public void setId(@Nullable String id) {
            this.id = id;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public @Nullable String getWhen() {
            return when;
        }

        public void setWhen(@Nullable String when) {
            this.when = when;
        }

        public Set<Redaction.Target> getTarget() {
            return target;
        }

        public void setTarget(Set<Redaction.Target> target) {
            this.target = target;
        }

        public List<String> getRedactors() {
            return redactors;
        }

        public void setRedactors(List<String> redactors) {
            this.redactors = redactors;
        }
    }

    /** Receipt keys and stream. */
    public static class Receipts {

        /**
         * Ed25519 private key that signs receipts, PKCS#8 DER encoded as Base64. When unset, a key is generated at
         * startup and receipts cannot be verified after a restart.
         */
        private @Nullable String signingKey;

        /**
         * Secret for the keyed hash of tool arguments stored in receipts, at least 32 bytes, Base64. When unset, a
         * key is generated at startup. Keep it out of the audit store.
         */
        private @Nullable String argsHmacKey;

        /** Id of this instance's receipt stream. Defaults to the application name plus a random suffix. */
        private @Nullable String streamId;

        public @Nullable String getSigningKey() {
            return signingKey;
        }

        public void setSigningKey(@Nullable String signingKey) {
            this.signingKey = signingKey;
        }

        public @Nullable String getArgsHmacKey() {
            return argsHmacKey;
        }

        public void setArgsHmacKey(@Nullable String argsHmacKey) {
            this.argsHmacKey = argsHmacKey;
        }

        public @Nullable String getStreamId() {
            return streamId;
        }

        public void setStreamId(@Nullable String streamId) {
            this.streamId = streamId;
        }
    }

    /** Audit settings. */
    public static class Audit {

        /** Refuse a call when its receipt cannot be stored. When false, the failure is logged and the call goes on. */
        private boolean strict = false;

        /** How many receipts the default in-memory audit sink keeps; the oldest are dropped first. */
        // A literal, not InMemoryAuditSink.DEFAULT_CAPACITY, so the configuration metadata shows the default.
        private int inMemoryCapacity = 10_000;

        /** The PostgreSQL audit sink, used when countersign-audit-jdbc is on the classpath and a DataSource exists. */
        private Jdbc jdbc = new Jdbc();

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
        }

        public int getInMemoryCapacity() {
            return inMemoryCapacity;
        }

        public void setInMemoryCapacity(int inMemoryCapacity) {
            this.inMemoryCapacity = inMemoryCapacity;
        }

        public Jdbc getJdbc() {
            return jdbc;
        }

        public void setJdbc(Jdbc jdbc) {
            this.jdbc = jdbc;
        }
    }

    /** PostgreSQL audit sink settings. */
    public static class Jdbc {

        /**
         * Create the countersign_receipt table and its append-only triggers at startup if the table does not exist.
         * Turn off when the schema is managed by Flyway or Liquibase.
         */
        private boolean initializeSchema = true;

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }
}
