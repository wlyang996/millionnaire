package com.millionnaire.engine.core.state;

/** Persistent authorized intent, independent of the actual (possibly interrupted) segment. */
public record MovePlan(MoveKind kind, int distance, long landingId, int cursor) { }
