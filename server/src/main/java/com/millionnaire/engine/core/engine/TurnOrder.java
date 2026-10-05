package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.state.OrderDraw;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 开局定序（R1、D#4、O38）：每人一个抽数序列（首抽 + 重抽），按序列逐位<b>降序</b>比较排序。
 * 序列完全相同的玩家构成同分子组，<b>只在组内</b>重抽（已分开的玩家不再混抽，不改变其他组的相对位置）。
 * 重抽顺序固定：先按名次从高到低处理各同分组，组内按座位（入房先后）顺序。纯函数，决策与校验共用。
 */
final class TurnOrder {
    private TurnOrder() {
    }

    /** 按抽数序列降序排列（稳定：完全相同时保持座位顺序）。 */
    static List<OrderDraw> sorted(List<OrderDraw> draws) {
        List<OrderDraw> out = new ArrayList<>(draws);
        out.sort(BY_DRAWS_DESC);
        return out;
    }

    /** 仍需重抽的同分子组（按名次从高到低，组内按座位顺序）；为空表示已全部分开。 */
    static List<List<String>> tiedGroups(List<OrderDraw> draws) {
        List<OrderDraw> s = sorted(draws);
        List<List<String>> groups = new ArrayList<>();
        int i = 0;
        while (i < s.size()) {
            int j = i + 1;
            while (j < s.size() && s.get(j).draws().equals(s.get(i).draws())) {
                j++;
            }
            if (j - i > 1) {
                // 组内按座位顺序（draws 的原始顺序即座位顺序）
                List<String> ids = s.subList(i, j).stream().map(OrderDraw::playerId).toList();
                groups.add(draws.stream().map(OrderDraw::playerId).filter(ids::contains).toList());
            }
            i = j;
        }
        return groups;
    }

    /** 最终行动顺序；仍有并列时抛出（调用方须先重抽到全部分开）。 */
    static List<String> order(List<OrderDraw> draws) {
        if (!tiedGroups(draws).isEmpty()) {
            throw new IllegalStateException("turn order still has ties: " + tiedGroups(draws));
        }
        return sorted(draws).stream().map(OrderDraw::playerId).toList();
    }

    private static final Comparator<OrderDraw> BY_DRAWS_DESC = (a, b) -> {
        List<Integer> x = a.draws();
        List<Integer> y = b.draws();
        for (int k = 0; k < Math.min(x.size(), y.size()); k++) {
            int c = Integer.compare(y.get(k), x.get(k));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.size(), y.size()) == 0 ? 0 : Integer.compare(y.size(), x.size());
    };
}
