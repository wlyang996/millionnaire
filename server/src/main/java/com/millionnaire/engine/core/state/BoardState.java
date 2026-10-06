package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/** 对局棋盘：模板 ID 与全部可拥有格子（按格号升序，每个普通地产与车站恰好一项）。路障等在 M3 加入。 */
public record BoardState(String boardId, List<OwnableState> ownables) {
    public BoardState {
        ownables = Immutable.list(ownables);
    }

    public Optional<OwnableState> ownable(int tile) {
        return ownables.stream().filter(o -> o.tile() == tile).findFirst();
    }

    public BoardState with(OwnableState value) {
        return new BoardState(boardId, ownables.stream().map(o -> o.tile() == value.tile() ? value : o).toList());
    }

    public List<OwnableState> ownedBy(String playerId) {
        return ownables.stream().filter(o -> playerId.equals(o.owner())).toList();
    }
}
