package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 地图模板：环形格子序列与人数容量；fixedEvents 是幸运格（FIXED_EVENT）与不幸格（UNLUCKY_EVENT，unlucky=true）的奖池，停下时按权重抽一项。 */
public record BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles, List<FixedEvent> fixedEvents) {
    public BoardTemplate {
        tiles = Immutable.list(tiles);
        fixedEvents = Immutable.list(fixedEvents == null ? List.of() : fixedEvents);
    }

    public BoardTemplate(String id, int minPlayers, int maxPlayers, List<Tile> tiles) {
        this(id, minPlayers, maxPlayers, tiles, List.of());
    }

    /** 某种格子（FIXED_EVENT 幸运 / UNLUCKY_EVENT 不幸）对应的奖池，保持配置顺序。 */
    public List<FixedEvent> pool(TileType type) {
        boolean unlucky = type == TileType.UNLUCKY_EVENT;
        return fixedEvents.stream().filter(f -> f.unlucky() == unlucky).toList();
    }

    /** 奖池的权重合计。 */
    public static int weight(List<FixedEvent> pool) {
        return pool.stream().mapToInt(FixedEvent::weight).sum();
    }

    /** 按抽到的权重位置 [0, weight) 取奖池下标。 */
    public static int pick(List<FixedEvent> pool, int roll) {
        int acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += pool.get(i).weight();
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
