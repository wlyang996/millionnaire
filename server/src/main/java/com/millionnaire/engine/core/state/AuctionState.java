package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.TreeSet;

/**
 * 进行中的拍卖（requirements 第 8 节、已采纳默认值 #11、O13）：
 * <ul>
 *   <li>LAND：落在无主指定拍卖地的玩家（initiator）放弃购买资格发起；以原价为基数；成交款归系统，发起人得 10%（向下取整）；
 *       发起人不能出价；</li>
 *   <li>CARD：拍卖卡拍卖自己未抵押的地产或车站（seller，排队申请、在安全点启动）；以标准价值为基数；成交款全归卖家；卖家不能出价；
 *       拍卖期间该资产锁定。</li>
 * </ul>
 * 起拍 = 基数 50%（向上取整），最小加价 = 基数 10%（向上取整），封顶 / 一口价 = 基数 2.5 倍（向下取整）；达到封顶的报价可不足最小加价。
 * 最高报价冻结出价者的现金，被超过立即解冻；hardEnd 为总时长 40 秒的上限。bidders 为出过价的玩家（认输延后的参与者）。
 */
public record AuctionState(Kind kind, int tile, String seller, String initiator, long basis, long start, long minRaise,
                           long cap, long highBid, String highBidder, long hardEnd, List<String> bidders) {
    public enum Kind { LAND, CARD }

    public AuctionState {
        bidders = Immutable.list(bidders);
    }

    /** 不能出价的一方（卖家或发起人）。 */
    public String host() {
        return kind == Kind.CARD ? seller : initiator;
    }

    public AuctionState bid(String bidder, long amount) {
        TreeSet<String> s = new TreeSet<>(bidders);
        s.add(bidder);
        return new AuctionState(kind, tile, seller, initiator, basis, start, minRaise, cap, amount, bidder, hardEnd, List.copyOf(s));
    }

    /** 这次报价的下限：无人出价时为起拍价，否则为当前最高价 + 最小加价（达到封顶时允许不足）。 */
    public long minimumBid() {
        return highBidder == null ? start : Math.min(cap, Math.addExact(highBid, minRaise));
    }
}
