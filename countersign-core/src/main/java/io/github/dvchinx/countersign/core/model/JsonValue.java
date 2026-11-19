package io.github.dvchinx.countersign.core.model;

import org.jspecify.annotations.Nullable;

/**
 * A JSON value: the shape of the arguments a model sends to a tool.
 *
 * <p>Countersign keeps its own small JSON tree instead of exposing a JSON library type, so that
 * {@code countersign-core} stays free of third-party dependencies and users are not tied to one Jackson major
 * version (see ADR 0002). Adapters parse the raw tool input with whatever library they already have and convert it
 * to this tree.
 *
 * <p>All implementations are immutable, so an invocation cannot be changed after policies have looked at it.
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    /**
     * Converts this value to plain Java objects: {@link java.util.Map}, {@link java.util.List}, {@link String},
     * {@link java.math.BigDecimal}, {@link Boolean} or {@code null}.
     *
     * <p>This is the form expression languages understand natively, for example SpEL's {@code #args['amount']}. The
     * returned collections are unmodifiable, so a condition cannot change the arguments it is checking.
     *
     * @return the equivalent Java value, or {@code null} for {@link JsonNull}
     */
    @Nullable Object toJava();
}
