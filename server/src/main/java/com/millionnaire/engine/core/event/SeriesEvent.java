package com.millionnaire.engine.core.event;

import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SeriesState.ScoredGame;
import java.util.List;
import com.millionnaire.engine.serialize.Immutable;

public sealed interface SeriesEvent extends Event {
    @Override default Visibility visibility() { return Visibility.PUBLIC; }
    record SeriesConfigured(long seriesNo, int totalGames) implements SeriesEvent { }
    record SeriesStarted(long seriesNo, List<Member> roster, RoomSettings settings) implements SeriesEvent {
        public SeriesStarted { roster = Immutable.list(roster); }
    }
    /** Informational fact emitted immediately after the authoritative GameEnded transition. */
    record SeriesGameScored(long seriesNo, ScoredGame game) implements SeriesEvent { }
    record SeriesEnded(long seriesNo) implements SeriesEvent { }
}
