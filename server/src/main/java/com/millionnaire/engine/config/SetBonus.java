package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 同组地产加成（2026-10-08，管理后台可配）：groups 为地图 ID → 逐格组号（0 = 不分组，只给普通地产编组）。
 * 同一组的地产全部归同一人且都未抵押时，组内每块地的租金 × rentPercent%（向下取整到 10）。rentPercent = 100 即关闭。
 */
public record SetBonus(int rentPercent, Map<String, List<Integer>> groups) {
    public static final SetBonus NONE = new SetBonus(100, new TreeMap<>());
    public static final int DEFAULT_RENT_PERCENT = 150;

    public SetBonus {
        Map<String, List<Integer>> copy = new TreeMap<>();
        if (groups != null) {
            groups.forEach((k, v) -> copy.put(k, Immutable.list(v)));
        }
        groups = Immutable.sortedMap(copy);
    }

    /** 某格的组号（0 = 不分组）。 */
    public int groupOf(String boardId, int tile) {
        List<Integer> g = groups.get(boardId);
        return g == null || tile < 0 || tile >= g.size() ? 0 : g.get(tile);
    }

    /** 与某格同组的全部格子（含自己）；不分组时为空。 */
    public List<Integer> members(String boardId, int tile) {
        int id = groupOf(boardId, tile);
        List<Integer> out = new ArrayList<>();
        if (id <= 0) {
            return out;
        }
        List<Integer> g = groups.get(boardId);
        for (int i = 0; i < g.size(); i++) {
            if (g.get(i) == id) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 默认分组：环形棋盘四条边上的普通地产按顺序两两一组（一条边剩单块时并入前一组）。
     * cols / rows 为棋盘网格的列数与行数（周长 = 格数）。
     */
    public static List<Integer> bySides(BoardTemplate board, int cols, int rows) {
        int n = board.size();
        int[] corners = {0, cols - 1, cols + rows - 2, 2 * cols + rows - 3, n};
        Integer[] out = new Integer[n];
        java.util.Arrays.fill(out, 0);
        int next = 1;
        for (int s = 0; s < 4; s++) {
            List<Integer> props = new ArrayList<>();
            for (int i = corners[s]; i < corners[s + 1]; i++) {
                if (board.tiles().get(i).type() == TileType.PROPERTY) {
                    props.add(i);
                }
            }
            for (int k = 0; k + 1 < props.size(); k += 2) {
                boolean lastPair = k + 3 == props.size(); // 剩 3 块：最后一组 3 块
                out[props.get(k)] = next;
                out[props.get(k + 1)] = next;
                if (lastPair) {
                    out[props.get(k + 2)] = next;
                    k++;
                }
                next++;
            }
        }
        return List.of(out);
    }
}
