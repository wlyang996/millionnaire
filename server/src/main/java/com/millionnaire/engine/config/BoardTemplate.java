package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 地图模板：环形格子序列与人数容量；fixedEvents 是幸运格（FIXED_EVENT）的奖池，停在任一幸运格时按权重抽一项。 */
public record BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles, List<FixedEvent> fixedEvents) {
    public BoardTemplate {
        tiles = Immutable.list(tiles);
        fixedEvents = Immutable.list(fixedEvents == null ? List.of() : fixedEvents);
    }

    public BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles) {
        this(id, minPlayers, maxPlayers, tiles, List.of());
    }

    /** 幸运奖池的权重合计。 */
    public int luckyWeight() {
        return fixedEvents.stream().mapToInt(FixedEvent::weight).sum();
    }

    /** 按抽到的权重位置 [0, luckyWeight) 取奖池下标。 */
    public int luckyIndex(int roll) {
        int acc = 0;
        for (int i = 0; i < fixedEvents.size(); i++) {
            acc += fixedEvents.get(i).weight();
            if (roll < acc) {
                return i;
            }
        }
        throw new IllegalArgumentException("lucky roll out of range: " + roll);
    }

    public int size() {
        return tiles.size();
    }

    public long count(TileType type) {
        return tiles.stream().filter(t -> t.type() == type).count();
    }
}
