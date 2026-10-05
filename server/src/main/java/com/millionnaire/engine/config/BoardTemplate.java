package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 地图模板：环形格子序列与人数容量。 */
public record BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles) {
    public BoardTemplate {
        tiles = Immutable.list(tiles);
    }

    public int size() {
        return tiles.size();
    }

    public long count(TileType type) {
        return tiles.stream().filter(t -> t.type() == type).count();
    }
}
