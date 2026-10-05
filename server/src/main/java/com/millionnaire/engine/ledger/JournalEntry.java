package com.millionnaire.engine.ledger;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 一笔平衡分录：各腿变动之和必须为 0。reason 为业务原因，ref 指向流程/窗口等来源。 */
public record JournalEntry(long entryNo, String reason, String ref, List<Leg> legs) {
    public JournalEntry {
        legs = Immutable.list(legs);
    }
}
