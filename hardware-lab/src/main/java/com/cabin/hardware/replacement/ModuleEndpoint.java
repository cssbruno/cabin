package com.cabin.hardware.replacement;

import java.util.List;

/** Module-specific semantics; get codes are not assumed to be callback field numbers. */
public interface ModuleEndpoint extends AutoCloseable {
    default void command(int code, ModulePayload args) { throw new UnsupportedOperationException("Module command unavailable"); }
    default ModulePayload get(int code, ModulePayload args) { return null; }
    /** Return all cached tuples for this field, e.g. multiple radio presets or EQ bands. */
    default List<ModulePayload> cached(int field) { return java.util.Collections.emptyList(); }
    @Override default void close() { }
}
