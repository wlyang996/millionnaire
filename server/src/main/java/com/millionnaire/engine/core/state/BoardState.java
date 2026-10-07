package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/** 对局棋盘：产权与公开路障分别按格号升序保存；路障所有者独立于产权与玩家清算。 */
public record BoardState(String boardId, List<OwnableState> ownables, List<Roadblock> roadblocks, long lastRoadblockId) {
    public BoardState {
        ownables = Immutable.list(ownables);
        roadblocks = Immutable.list(roadblocks);
    }
    public BoardState(String boardId, List<OwnableState> ownables) { this(boardId, ownables, List.of(), 0); }

    public Optional<Roadblock> roadblock(int tile) {
        return roadblocks.stream().filter(r -> r.tile() == tile).findFirst();
    }

    public BoardState placed(Roadblock value) {
        if (roadblock(value.tile()).isPresent() || value.id() != Math.addExact(lastRoadblockId, 1)) {
            throw new IllegalStateException("duplicate roadblock tile or allocation");
        }
        var next = Immutable.append(roadblocks, value).stream()
                .sorted(java.util.Comparator.comparingInt(Roadblock::tile)).toList();
        return new BoardState(boardId, ownables, next, value.id());
    }

    /** Removal requires the exact object, never a bare tile or owner. */
    public BoardState triggered(Roadblock value) {
        if (!roadblock(value.tile()).map(value::equals).orElse(false)) {
            throw new IllegalStateException("roadblock trigger does not match board object");
        }
        return new BoardState(boardId, ownables, roadblocks.stream().filter(r -> r.id() != value.id()).toList(), lastRoadblockId);
    }

    public Optional<OwnableState> ownable(int tile) {
        return ownables.stream().filter(o -> o.tile() == tile).findFirst();
    }

    public BoardState with(OwnableState value) {
        return new BoardState(boardId, ownables.stream().map(o -> o.tile() == value.tile() ? value : o).toList(), roadblocks, lastRoadblockId);
    }

    public List<OwnableState> ownedBy(String playerId) {
        return ownables.stream().filter(o -> playerId.equals(o.owner())).toList();
    }
}
