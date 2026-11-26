/**
 * Redaction rules and the built-in redactors (ADR 0015). Redaction is an obligation on calls that run, not a
 * decision: every rule that matches a call that runs applies to it, whatever decided access. Redactors implement the
 * {@code Redactor} port and work on the core JSON tree.
 */
@NullMarked
package io.github.dvchinx.countersign.core.redaction;

import org.jspecify.annotations.NullMarked;
