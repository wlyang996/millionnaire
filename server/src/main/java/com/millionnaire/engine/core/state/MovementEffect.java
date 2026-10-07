package com.millionnaire.engine.core.state;

/** Internal one-use mechanism source. M4 must authorize/consume the real card before issuing this. */
public record MovementEffect(Kind kind, String playerId, long turnNo, long windowId, int from, int distance, long at) {
    public enum Kind { ROADBLOCK, TARGETED }
}
