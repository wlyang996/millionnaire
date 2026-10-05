package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 一位参与者的开局抽数序列：首抽 + 同分子组内的各次重抽（1..orderNumberMax）。 */
public record OrderDraw(String playerId, List<Integer> draws) {
    public OrderDraw {
        draws = Immutable.list(draws);
    }
}
