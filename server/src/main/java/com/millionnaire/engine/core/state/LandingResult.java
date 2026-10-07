package com.millionnaire.engine.core.state;
/** 任务的唯一消费结果；债务挂起不消费 RENT，偿付后消费。 */
public enum LandingResult { BOUGHT, DECLINED, UPGRADED, SKIPPED, BANK_FINISHED, PAID,
    DRAW_REWARD, DRAW_FINE, DRAW_CARD, DRAW_MOVE, DRAW_JAIL, REWARDED, CARD_RECEIVED, DISCARDED, MOVED,
    /** 小游戏已结束（输家确定、奖励已发）。 */
    PLAYED,
    /** 免租卡免除了本次租金。 */
    WAIVED,
    /** 指定拍卖地：放弃购买资格，发起土地拍卖。 */
    AUCTIONED,
    /** 土地拍卖已结束（成交或流拍）。 */
    AUCTION_ENDED }
