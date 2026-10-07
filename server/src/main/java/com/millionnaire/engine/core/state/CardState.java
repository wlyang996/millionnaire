package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.TreeSet;

/**
 * 道具（requirements 第 14 节、已采纳默认值 #9 / #10 / #17、O4）的对局状态：
 * <ul>
 *   <li>chanceUsed：本轮已用掉主动用卡机会的玩家（升序）。每个自己的回合开始恢复一次；响应卡不占机会；</li>
 *   <li>boughtTile：本回合刚买下的格子（-1 = 无）。建造卡不能用在刚买下的地上（与"买地后不能马上升级"一致）；</li>
 *   <li>effect：已用出、尚未结算的卡（步内衔接；跨步只会是"等待对方响应"的攻击卡，此时 response 为对方可用的响应卡）。</li>
 * </ul>
 */
public record CardState(List<String> chanceUsed, int boughtTile, Effect effect) {
    public static final CardState EMPTY = new CardState(List.of(), -1, null);

    public CardState {
        chanceUsed = Immutable.list(chanceUsed);
    }

    /** 已用出的卡：使用者、卡、目标格（-1 = 无）、目标玩家（地产所有者或查询对象）、等待中的响应卡（null = 不等待）。 */
    public record Effect(String actor, CardType card, int tile, String target, CardType response) {
        public Effect awaiting(CardType value) {
            return new Effect(actor, card, tile, target, value);
        }
    }

    public boolean used(String playerId) {
        return chanceUsed.contains(playerId);
    }

    public CardState use(String playerId) {
        TreeSet<String> s = new TreeSet<>(chanceUsed);
        s.add(playerId);
        return new CardState(List.copyOf(s), boughtTile, effect);
    }

    /** 自己的回合开始：恢复主动用卡机会，清除上回合的买地记录。 */
    public CardState turnStarted(String playerId) {
        return new CardState(chanceUsed.stream().filter(p -> !p.equals(playerId)).toList(), -1, effect);
    }

    public CardState bought(int tile) {
        return new CardState(chanceUsed, tile, effect);
    }

    public CardState effect(Effect value) {
        return new CardState(chanceUsed, boughtTile, value);
    }
}
