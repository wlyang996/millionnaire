package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.EventKind;

/** Result fixed by audited draws. Card faces stay in the private hand, never in the public result. */
public record EventResolution(EventKind kind, long amount, MoveKind moveKind, int distance,
                              int newCardIndex, boolean countPending, int cardDraw) {
    public EventResolution(EventKind kind, long amount, MoveKind moveKind, int distance, int index, boolean pending) {
        this(kind, amount, moveKind, distance, index, pending, -1);
    }
    public EventResolution received(int index, int draw) {
        return new EventResolution(kind, amount, moveKind, distance, index, true, draw);
    }
    public EventResolution countReported() {
        return new EventResolution(kind, amount, moveKind, distance, newCardIndex, false, cardDraw);
    }
    public EventResolution discarded() {
        return new EventResolution(kind, amount, moveKind, distance, newCardIndex, true, cardDraw);
    }
}
