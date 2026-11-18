/**
 * Spring AI adapter. Decorates {@code ToolCallback}s, including MCP tools, so every call goes through the
 * policy engine where the tool runs, not only in the tool list sent to the model.
 */
@NullMarked
package io.github.dvchinx.countersign.springai;

import org.jspecify.annotations.NullMarked;
