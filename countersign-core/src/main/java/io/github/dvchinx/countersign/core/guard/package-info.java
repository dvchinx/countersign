/**
 * The decision path for one tool call, independent of any framework: evaluate the policies, record a signed receipt,
 * publish it, and tell the adapter whether the tool may run. Adapters (Spring AI, MCP) only translate their own types
 * to and from this package.
 */
@NullMarked
package io.github.dvchinx.countersign.core.guard;

import org.jspecify.annotations.NullMarked;
