package com.millionnaire.engine.core.state;

/** Public board object; owner is the permanent game roster ID, even after elimination. */
public record Roadblock(long id, int tile, String owner, long placedTurn) { }
