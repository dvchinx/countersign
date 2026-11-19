package io.github.dvchinx.countersign.core.model;

import org.jspecify.annotations.Nullable;

/** The JSON {@code null} literal. A singleton, so that {@code null} arguments can be compared with {@code ==}. */
public enum JsonNull implements JsonValue {
    /** The only instance. */
    INSTANCE;

    @Override
    public @Nullable Object toJava() {
        return null;
    }

    @Override
    public String toString() {
        return "null";
    }
}
