package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import io.github.dvchinx.countersign.springai.spel.SpelCondition;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** The banking demo's policies and a guard built from them, shared by the integration tests. */
final class BankPolicies {

    static final KeyPair KEYS = keys();

    static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("no-destructive-sql")
                            .tools("db.*")
                            .when(SpelCondition.parse("#args['sql'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"))
                            .decision(Decision.deny("Destructive SQL is not allowed from agents"))
                            .build(),
                    Policy.builder("db-reads")
                            .tools("db.*")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("transfers-need-approval")
                            .tools("payments.transfer")
                            .when(SpelCondition.parse("#args['amount'] > 1000"))
                            .decision(Decision.requireApproval("Moves real money"))
                            .build(),
                    Policy.builder("small-transfers")
                            .tools("payments.transfer")
                            .decision(Decision.allow())
                            .build()),
            Decision.deny("No policy allows this call"));

    private BankPolicies() {}

    static ToolCallGuard guard(ReceiptSigner signer, AuditSink sink) {
        return builder(signer, sink).build();
    }

    static ToolCallGuard.Builder builder(ReceiptSigner signer, AuditSink sink) {
        return ToolCallGuard.builder(
                ENGINE, ReceiptChain.start("bank-1", signer, new ArgumentsHmac(new byte[32]), Clock.systemUTC()), sink);
    }

    static ToolCallGuard guard(AuditSink sink, ApprovalGateway approvals, Duration timeout) {
        return builder(new Ed25519ReceiptSigner(KEYS.getPrivate()), sink)
                .approvals(approvals)
                .approvalTimeout(timeout)
                .build();
    }

    static ToolCallGuard guard(AuditSink sink) {
        return guard(new Ed25519ReceiptSigner(KEYS.getPrivate()), sink);
    }

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
