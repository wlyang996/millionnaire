package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;

/**
 * 加权抽牌（R2/R7）：在 [0, 权重总和) 上均匀抽一个数，按 {@link CardType} 声明顺序累加权重落入的区间即为牌型。
 * 声明顺序属于随机协议的一部分，调整顺序必须提升引擎版本。
 */
final class CardDeck {
    private CardDeck() {
    }

    static int totalWeight(RuleConfig config) {
        int total = 0;
        for (CardType t : CardType.values()) {
            total = Math.addExact(total, config.cardWeights().get(t));
        }
        return total;
    }

    static CardType pick(RuleConfig config, int value) {
        int cumulative = 0;
        for (CardType t : CardType.values()) {
            cumulative += config.cardWeights().get(t);
            if (value < cumulative) {
                return t;
            }
        }
        throw new IllegalStateException("card draw " + value + " out of range");
    }
}
