package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 地图模板：环形格子序列与人数容量；fixedEvents 按顺序对应棋盘上的固定事件格。 */
public record BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles, List<FixedEvent> fixedEvents) {
    public BoardTemplate {
        tiles = Immutable.list(tiles);
        fixedEvents = Immutable.list(fixedEvents == null ? List.of() : fixedEvents);
    }

    public BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles) {
        this(id, minPlayers, maxPlayers, tiles, List.of());
    }

    /** 某个固定事件格的效果（第 k 个固定事件格对应 fixedEvents 第 k 项）。 */
    public FixedEvent fixedEvent(int tileIndex) {
        int k = 0;
        for (Tile t : tiles) {
            if (t.type() == TileType.FIXED_EVENT) {
                if (t.index() == tileIndex) {
                    return fixedEvents.get(k);
                }
                k++;
            }
        }
        throw new IllegalArgumentException("not a fixed event tile: " + tileIndex);
    }

    public int size() {
        return tiles.size();
    }

    public long count(TileType type) {
        return tiles.stream().filter(t -> t.type() == type).count();
    }
}
