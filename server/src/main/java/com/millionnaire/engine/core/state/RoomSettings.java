package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;

/**
 * 房主可修改的开局设置（requirements 第 2 节）。
 * initialCards：开局每人随机发几张道具（0 = 不发，最多为手牌上限）；{@link #AS_CONFIG} 表示沿用规则配置的 initialHandSize
 * （旧局、测试夹具与五参数构造器的语义，行为与加入此项之前完全一致）。正式配置（道具开启）建房默认 0（用户 2026-10-07 决定）。
 */
public record RoomSettings(String boardId, long initialCash, EndMode endMode, int timeLimitMinutes, int rollSeconds, int initialCards) {
    /** 开局道具数沿用配置的 initialHandSize。 */
    public static final int AS_CONFIG = -1;

    public RoomSettings(String boardId, long initialCash, EndMode endMode, int timeLimitMinutes, int rollSeconds) {
        this(boardId, initialCash, endMode, timeLimitMinutes, rollSeconds, AS_CONFIG);
    }

    public static RoomSettings defaults(RuleConfig config) {
        var r = config.room();
        return new RoomSettings(r.defaultBoardId(), r.defaultInitialCash(), r.defaultEndMode(),
                r.defaultTimeLimitMinutes(), r.defaultRollSeconds(), config.economy().cardsEnabled() ? 0 : AS_CONFIG);
    }

    /** 开局每人实际发牌数。 */
    public int dealCount(RuleConfig config) {
        return initialCards < 0 ? config.economy().initialHandSize() : initialCards;
    }

    /** 改开局道具数（其余不变）。 */
    public RoomSettings withInitialCards(int n) {
        return new RoomSettings(boardId, initialCash, endMode, timeLimitMinutes, rollSeconds, n);
    }
}
