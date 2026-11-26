/**
 * Ports to the outside world ({@code AuditSink}, {@code ApprovalGateway}, {@code ReceiptSigner},
 * {@code DecisionListener}, {@code PrincipalResolver}, {@code Redactor}). Each port is kept to one method where possible so
 * it can be implemented with a lambda.
 * Part of the public API.
 */
@NullMarked
package io.github.dvchinx.countersign.core.spi;

import org.jspecify.annotations.NullMarked;
