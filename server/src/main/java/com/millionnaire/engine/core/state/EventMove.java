package com.millionnaire.engine.core.state;
/** One-step relocation authority, issued only by consuming the current MOVE/JAIL task. */
public record EventMove(long landingId, int cursor, String playerId, MoveKind kind, int distance) { }
