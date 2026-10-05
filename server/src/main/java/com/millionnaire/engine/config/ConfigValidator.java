package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Money;
import com.millionnaire.engine.money.Ratio;
import com.millionnaire.engine.money.Rounding;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * 规则配置校验，分两层：
 * <ol>
 *   <li>通用校验：缺失、比例合法性（分母为正、分子为正、指定比例不超过 100%）、计数与金额范围、
 *       以及在受检运算下实际计算全部派生金额（标准价值、拍卖、强购、交易区间、抵押、手续费、车站最大租金、
 *       初始现金总额、时间换算），溢出或"未定义取整方向却非整数"都报为配置错误；</li>
 *   <li>规格校验：按 ruleVersion 选择（目前仅 {@link RulesV1Spec}），未知版本即错误。</li>
 * </ol>
 * 非法配置只会得到可定位的错误信息，不会通过，也不会抛出运行时异常。
 */
public final class ConfigValidator {
    /** 道具权重千分比合计。 */
    public static final int CARD_WEIGHT_TOTAL = 1000;
    /** 事件权重百分比合计。 */
    public static final int EVENT_WEIGHT_TOTAL = 100;
    /** 房间人数上限（requirements 第 1 节）。 */
    public static final int MAX_PLAYERS = 8;
    /** 单个配置金额与派生金额的上限，保证全局求和（净资产、守恒）远离 long 溢出。 */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    /** 派生金额（标准价值、封顶等）与时间换算结果的上限；8 人 × 全部地产求和仍远小于 long 上限。 */
    public static final long MAX_DERIVED = 1_000_000_000_000_000L;
    /** 毫秒类时长上限：一天。 */
    public static final long MAX_DURATION_MS = 86_400_000L;
    /** 一次等概率抽取的候选数上限（RandomSource.nextInt 的 int 上界）。 */
    public static final long MAX_DRAW_CANDIDATES = Integer.MAX_VALUE;

    private final List<String> errors = new ArrayList<>();

    private ConfigValidator() {
    }

    /** 返回全部错误；空列表表示通过。 */
    public static List<String> validate(RuleConfig c) {
        ConfigValidator v = new ConfigValidator();
        v.check(c);
        if (v.errors.isEmpty()) {
            if (RulesV1Spec.RULE_VERSION.equals(c.ruleVersion())) {
                v.errors.addAll(RulesV1Spec.validate(c));
            } else {
                v.fail("no rule specification for ruleVersion " + c.ruleVersion());
            }
        }
        return List.copyOf(v.errors);
    }

    public static RuleConfig validateOrThrow(RuleConfig c) {
        List<String> errors = validate(c);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return c;
    }

    private void check(RuleConfig c) {
        if (c == null) {
            fail("config missing");
            return;
        }
        if (blank(c.ruleVersion())) {
            fail("ruleVersion missing");
        }
        EconomyConfig eco = c.economy();
        boolean ecoOk = present(eco, "economy") && checkEconomy(eco);
        RatioConfig ratios = c.ratios();
        boolean ratiosOk = present(ratios, "ratios") && checkRatios(ratios);
        boolean boardsOk = present(c.boards(), "boards") && ecoOk && checkBoards(c.boards(), eco);
        if (present(c.tiers(), "tiers") && ecoOk && ratiosOk) {
            checkTiers(c.tiers(), eco, ratios);
        }
        if (present(c.station(), "station") && ratiosOk && boardsOk) {
            checkStation(c.station(), ratios, c.boards());
        }
        if (present(c.cardWeights(), "cardWeights")) {
            checkWeights("cardWeights", c.cardWeights(), CardType.values().length, CARD_WEIGHT_TOTAL);
        }
        if (present(c.eventWeights(), "eventWeights")) {
            checkWeights("eventWeights", c.eventWeights(), EventKind.values().length, EVENT_WEIGHT_TOTAL);
        }
        boolean timingOk = present(c.timing(), "timing") && checkTiming(c.timing());
        if (present(c.room(), "room") && timingOk && boardsOk) {
            checkRoom(c);
        }
    }

    private boolean checkEconomy(EconomyConfig e) {
        int before = errors.size();
        amount("economy.startReward", e.startReward());
        amount("economy.miniGameWinReward", e.miniGameWinReward());
        amount("economy.bailCost", e.bailCost());
        amount("economy.eventCashMin", e.eventCashMin());
        amount("economy.eventCashMax", e.eventCashMax());
        amount("economy.eventCashStep", e.eventCashStep());
        if (errors.size() == before && (e.eventCashMax() < e.eventCashMin()
                || (e.eventCashMax() - e.eventCashMin()) % e.eventCashStep() != 0)) {
            fail("economy.eventCash range must be min..max by step");
        } else if (errors.size() == before
                && (e.eventCashMax() - e.eventCashMin()) / e.eventCashStep() + 1 > MAX_DRAW_CANDIDATES) {
            // 事件金额按 nextInt(候选数) 等概率抽取，候选数必须在 int 上界内
            fail("economy.eventCash candidate count " + ((e.eventCashMax() - e.eventCashMin()) / e.eventCashStep() + 1)
                    + " exceeds the random draw bound " + MAX_DRAW_CANDIDATES);
        }
        count("economy.eventMoveMinSteps", e.eventMoveMinSteps(), 1, 100);
        count("economy.eventMoveMaxSteps", e.eventMoveMaxSteps(), 1, 100);
        if (e.eventMoveMaxSteps() < e.eventMoveMinSteps()) {
            fail("economy.eventMoveMaxSteps < eventMoveMinSteps");
        }
        count("economy.dieFaces", e.dieFaces(), 1, 100);
        count("economy.maxLevel", e.maxLevel(), 1, 10);
        count("economy.handLimit", e.handLimit(), 1, 100);
        count("economy.initialHandSize", e.initialHandSize(), 1, 100);
        if (e.handLimit() < e.initialHandSize()) {
            fail("economy.handLimit < initialHandSize");
        }
        count("economy.orderNumberMax", e.orderNumberMax(), 1, 1_000_000);
        return errors.size() == before;
    }

    private boolean checkRatios(RatioConfig r) {
        int before = errors.size();
        ratio("ratios.upgradeValueShare", r.upgradeValueShare(), true);
        ratio("ratios.bankMortgage", r.bankMortgage(), true);
        ratio("ratios.redeemFeeOffBank", r.redeemFeeOffBank(), true);
        ratio("ratios.auctionStart", r.auctionStart(), false);
        ratio("ratios.auctionMinRaise", r.auctionMinRaise(), true);
        ratio("ratios.auctionCap", r.auctionCap(), false);
        ratio("ratios.forcedPurchase", r.forcedPurchase(), false);
        ratio("ratios.tradeMin", r.tradeMin(), false);
        ratio("ratios.tradeMax", r.tradeMax(), false);
        // 佣金是成交款的一部分，不能超过成交款
        ratio("ratios.systemAuctionCommission", r.systemAuctionCommission(), true);
        if (errors.size() == before && compare(r.tradeMin(), r.tradeMax()) > 0) {
            fail("ratios.tradeMin > tradeMax");
        }
        if (errors.size() == before && compare(r.auctionStart(), r.auctionCap()) > 0) {
            fail("ratios.auctionStart > auctionCap");
        }
        return errors.size() == before;
    }

    private boolean checkBoards(List<BoardTemplate> boards, EconomyConfig eco) {
        int before = errors.size();
        if (boards.isEmpty()) {
            fail("boards empty");
        }
        TreeSet<String> ids = new TreeSet<>();
        int longestChain = Math.addExact(eco.dieFaces(), eco.eventMoveMaxSteps());
        for (BoardTemplate b : boards) {
            if (!present(b, "board")) {
                continue;
            }
            String p = "board[" + b.id() + "]";
            if (blank(b.id()) || !ids.add(b.id())) {
                fail(p + " id missing or duplicated");
            }
            if (b.minPlayers() < 2 || b.maxPlayers() < b.minPlayers() || b.maxPlayers() > MAX_PLAYERS) {
                fail(p + " player capacity must satisfy 2 <= min <= max <= " + MAX_PLAYERS);
            }
            if (!present(b.tiles(), p + ".tiles")) {
                continue;
            }
            if (b.tiles().isEmpty() || b.tiles().size() > 1000) {
                fail(p + " tile count must be 1..1000");
                continue;
            }
            int jails = 0;
            int starts = 0;
            for (int i = 0; i < b.tiles().size(); i++) {
                Tile t = b.tiles().get(i);
                if (t == null || t.type() == null) {
                    fail(p + " tile " + i + " missing");
                    continue;
                }
                if (t.index() != i) {
                    fail(p + " tile index " + t.index() + " at position " + i);
                }
                boolean property = t.type() == TileType.PROPERTY;
                if (property != (t.tier() != null)) {
                    fail(p + " tile " + i + " tier must be set iff PROPERTY");
                }
                if (t.auctionDesignated() && !property) {
                    fail(p + " tile " + i + " auction designation only on PROPERTY");
                }
                starts += t.type() == TileType.START ? 1 : 0;
                jails += t.type() == TileType.JAIL ? 1 : 0;
            }
            if (starts != 1 || b.tiles().get(0) == null || b.tiles().get(0).type() != TileType.START) {
                fail(p + " must have exactly one START at index 0");
            }
            if (jails != 1) {
                fail(p + " must have exactly one JAIL");
            }
            // 单回合最长前进链（骰子 + 事件位移）必须小于格子数，使"超过一圈"不可达
            if (longestChain >= b.tiles().size()) {
                fail(p + " too small for max forward chain " + longestChain);
            }
        }
        return errors.size() == before;
    }

    private void checkTiers(List<TierPricing> tiers, EconomyConfig eco, RatioConfig r) {
        TreeSet<Tier> seen = new TreeSet<>();
        for (TierPricing t : tiers) {
            if (!present(t, "tier") || !present(t.tier(), "tier.tier")) {
                continue;
            }
            String p = "tier[" + t.tier() + "]";
            if (!seen.add(t.tier())) {
                fail(p + " duplicated");
            }
            boolean ok = amount(p + ".basePrice", t.basePrice()) & amount(p + ".upgradeCost", t.upgradeCost());
            if (present(t.rents(), p + ".rents")) {
                if (t.rents().size() != eco.maxLevel() + 1) {
                    fail(p + ".rents must have maxLevel+1 entries");
                }
                long prev = 0;
                for (Long rent : t.rents()) {
                    if (rent == null || rent <= prev || rent > MAX_AMOUNT) {
                        fail(p + ".rents must be positive, strictly increasing and <= " + MAX_AMOUNT);
                        break;
                    }
                    prev = rent;
                }
            }
            boolean emergencyOk = ratio(p + ".emergencyMortgage", t.emergencyMortgage(), true);
            if (!ok) {
                continue;
            }
            for (int level = 0; level <= eco.maxLevel(); level++) {
                String lp = p + " level " + level;
                int lv = level;
                long levelCost = derived(lp + " upgrade cost", () -> Money.mul(t.upgradeCost(), lv));
                if (levelCost < 0 || !exact(lp + " standard value", levelCost, r.upgradeValueShare())) {
                    continue;
                }
                long std = derived(lp + " standard value", () -> Money.add(t.basePrice(), r.upgradeValueShare().applyExact(levelCost)));
                if (std >= 0) {
                    priceDerivatives(lp, std, r);
                }
            }
            if (emergencyOk) {
                checkMortgage(p, t.basePrice(), t.emergencyMortgage(), r);
            }
        }
        for (Tier tier : Tier.values()) {
            if (!seen.contains(tier)) {
                fail("tier[" + tier + "] missing");
            }
        }
    }

    private void checkStation(StationPricing s, RatioConfig r, List<BoardTemplate> boards) {
        boolean ok = amount("station.price", s.price()) & amount("station.rentPerStation", s.rentPerStation());
        boolean emergencyOk = ratio("station.emergencyMortgage", s.emergencyMortgage(), true);
        if (!ok) {
            return;
        }
        priceDerivatives("station", s.price(), r);
        long maxStations = boards.stream().filter(b -> b != null && b.tiles() != null)
                .mapToLong(b -> b.count(TileType.STATION)).max().orElse(0);
        derived("station max rent", () -> Money.mul(maxStations, s.rentPerStation()));
        if (emergencyOk) {
            checkMortgage("station", s.price(), s.emergencyMortgage(), r);
        }
    }

    /**
     * 由标准价值派生的拍卖、强购、交易区间金额：受检计算，并检查取整后的区间——
     * 需付费的价格必须 &gt; 0，起拍 ≤ 封顶，交易下限 ≤ 上限。
     */
    private void priceDerivatives(String p, long basis, RatioConfig r) {
        long start = paid(p + " auction start", () -> r.auctionStart().apply(basis, Rounding.CEIL));
        paid(p + " auction min raise", () -> r.auctionMinRaise().apply(basis, Rounding.CEIL));
        paid(p + " forced purchase", () -> r.forcedPurchase().apply(basis, Rounding.FLOOR));
        long tradeMin = paid(p + " trade min", () -> r.tradeMin().apply(basis, Rounding.CEIL));
        long tradeMax = paid(p + " trade max", () -> r.tradeMax().apply(basis, Rounding.FLOOR));
        long cap = paid(p + " auction cap", () -> r.auctionCap().apply(basis, Rounding.FLOOR));
        if (start > 0 && cap > 0 && start > cap) {
            fail(p + " auction start " + start + " exceeds cap " + cap + " after rounding");
        }
        if (tradeMin > 0 && tradeMax > 0 && tradeMin > tradeMax) {
            fail(p + " trade min " + tradeMin + " exceeds trade max " + tradeMax + " after rounding");
        }
        if (cap > 0) {
            derived(p + " system commission at cap", () -> r.systemAuctionCommission().apply(cap, Rounding.FLOOR));
        }
    }

    /** 需要玩家支付（或作为价格区间端点）的派生金额：受检计算且必须 &gt; 0；失败返回 -1。 */
    private long paid(String what, LongSupplier computation) {
        long v = derived(what, computation);
        if (v == 0) {
            fail(what + " rounds to 0; a payable price must be > 0");
            return -1;
        }
        return v;
    }

    /** 抵押额与赎回手续费没有规定取整方向，必须整除。 */
    private void checkMortgage(String p, long base, Ratio emergency, RatioConfig r) {
        if (exact(p + " bank mortgage", base, r.bankMortgage())) {
            exact(p + " redeem fee on bank principal", r.bankMortgage().applyExact(base), r.redeemFeeOffBank());
        }
        if (exact(p + " emergency mortgage", base, emergency)) {
            // 原价 ≥ 1、比例为正且整除，故抵押额必然 ≥ 1
            exact(p + " redeem fee on emergency principal", emergency.applyExact(base), r.redeemFeeOffBank());
        }
    }

    private <K> void checkWeights(String p, Map<K, Integer> weights, int expectedKeys, int total) {
        if (weights.size() != expectedKeys) {
            fail(p + " must define every kind (" + expectedKeys + "), got " + weights.size());
        }
        long sum = 0;
        for (Map.Entry<K, Integer> e : weights.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0 || e.getValue() > total) {
                fail(p + "." + e.getKey() + " must be in 1.." + total);
            } else {
                sum += e.getValue();
            }
        }
        if (sum != total) {
            fail(p + " must sum to " + total + ", got " + sum);
        }
    }

    private boolean checkTiming(TimingConfig t) {
        int before = errors.size();
        options("timing.rollSecondsOptions", t.rollSecondsOptions(), 3600, 1000);
        options("timing.timeLimitMinutesOptions", t.timeLimitMinutesOptions(), 1440, 60_000);
        count("timing.bankruptcyModeCapMinutes", t.bankruptcyModeCapMinutes(), 1, 1440);
        derived("timing.bankruptcyModeCapMinutes in ms", () -> Math.multiplyExact((long) t.bankruptcyModeCapMinutes(), 60_000L));
        duration("timing.decisionWindowMs", t.decisionWindowMs());
        duration("timing.responseWindowMs", t.responseWindowMs());
        duration("timing.discardWindowMs", t.discardWindowMs());
        duration("timing.tradeResponseMs", t.tradeResponseMs());
        duration("timing.toothPickMs", t.toothPickMs());
        duration("timing.auctionDurationMs", t.auctionDurationMs());
        duration("timing.auctionExtendMs", t.auctionExtendMs());
        duration("timing.auctionMaxMs", t.auctionMaxMs());
        duration("timing.debtSegmentMs", t.debtSegmentMs());
        duration("timing.heartbeatMs", t.heartbeatMs());
        duration("timing.suspectAfterMs", t.suspectAfterMs());
        duration("timing.offlineAfterMs", t.offlineAfterMs());
        duration("timing.allOfflineCloseMs", t.allOfflineCloseMs());
        duration("timing.downtimeBudgetMs", t.downtimeBudgetMs());
        duration("timing.recoveryPrepMs", t.recoveryPrepMs());
        if (t.animDiceMs() < 0 || t.animDiceMs() > 10_000 || t.animPerStepMs() < 0 || t.animPerStepMs() > 2_000) {
            fail("timing animation buffers must be within 0..10000 ms (dice) and 0..2000 ms (per step)");
        }
        if (t.autoActDelayMs() < 1 || t.autoActDelayMs() > 60_000) {
            fail("timing.autoActDelayMs must be in 1..60000");
        }
        if (t.auctionMaxMs() < t.auctionDurationMs()) {
            fail("timing.auctionMaxMs < auctionDurationMs");
        }
        if (!(t.heartbeatMs() < t.suspectAfterMs() && t.suspectAfterMs() < t.offlineAfterMs())) {
            fail("timing must satisfy heartbeat < suspect < offline");
        }
        return errors.size() == before;
    }

    private void checkRoom(RuleConfig c) {
        RoomOptions r = c.room();
        int smallestBoard = c.boards().stream().mapToInt(BoardTemplate::maxPlayers).min().orElse(0);
        if (r.minPlayersToStart() < 2 || r.minPlayersToStart() > smallestBoard) {
            fail("room.minPlayersToStart must be in 2.." + smallestBoard + " so every board can start");
        }
        if (present(r.initialCashOptions(), "room.initialCashOptions")) {
            if (r.initialCashOptions().isEmpty()) {
                fail("room.initialCashOptions empty");
            }
            long max = 0;
            boolean ok = true;
            TreeSet<Long> seen = new TreeSet<>();
            for (Long cash : r.initialCashOptions()) {
                if (cash == null) {
                    fail("room.initialCashOptions must not contain null");
                    ok = false;
                    continue;
                }
                if (!seen.add(cash)) {
                    fail("room.initialCashOptions contains duplicate " + cash);
                }
                ok &= amount("room.initialCashOptions", cash);
                max = Math.max(max, cash);
            }
            long top = max;
            if (ok && top > 0) {
                derived("room initial cash total", () -> Money.mul(top, MAX_PLAYERS));
            }
            if (!r.initialCashOptions().contains(r.defaultInitialCash())) {
                fail("room.defaultInitialCash not among options");
            }
        }
        if (c.board(r.defaultBoardId() == null ? "" : r.defaultBoardId()).isEmpty()) {
            fail("room.defaultBoardId unknown");
        }
        present(r.defaultEndMode(), "room.defaultEndMode");
        TimingConfig t = c.timing();
        if (!t.timeLimitMinutesOptions().contains(r.defaultTimeLimitMinutes())) {
            fail("room.defaultTimeLimitMinutes not among options");
        }
        if (!t.rollSecondsOptions().contains(r.defaultRollSeconds())) {
            fail("room.defaultRollSeconds not among options");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 在受检运算下计算派生金额并检查范围；失败记录错误并返回 -1。 */
    private long derived(String what, LongSupplier computation) {
        long v;
        try {
            v = computation.getAsLong();
        } catch (ArithmeticException e) {
            fail(what + " overflows or is not an integer: " + e.getMessage());
            return -1;
        }
        if (v < 0 || v > MAX_DERIVED) {
            fail(what + " out of range: " + v);
            return -1;
        }
        return v;
    }

    private boolean exact(String what, long amount, Ratio ratio) {
        try {
            if (!ratio.isExactFor(amount)) {
                fail(what + " is not an integer (" + amount + " x " + ratio.num() + "/" + ratio.den() + ")");
                return false;
            }
            return true;
        } catch (ArithmeticException e) {
            fail(what + " overflows: " + e.getMessage());
            return false;
        }
    }

    private void options(String what, List<Integer> values, int max, long unitMs) {
        if (!present(values, what)) {
            return;
        }
        if (values.isEmpty()) {
            fail(what + " empty");
        }
        int prev = 0;
        for (Integer v : values) {
            if (v == null || v <= prev || v > max) {
                fail(what + " must be strictly increasing within 1.." + max);
                return;
            }
            prev = v;
            derived(what + " in ms", () -> Math.multiplyExact((long) v, unitMs));
        }
    }

    /** 比例合法性：存在、分母 &gt; 0、分子 &gt; 0；atMostOne 时还要求 num ≤ den。 */
    private boolean ratio(String what, Ratio r, boolean atMostOne) {
        if (!present(r, what)) {
            return false;
        }
        if (r.den() <= 0) {
            fail(what + " denominator must be > 0, got " + r.den());
            return false;
        }
        if (r.num() <= 0) {
            fail(what + " numerator must be > 0, got " + r.num());
            return false;
        }
        if (atMostOne && r.num() > r.den()) {
            fail(what + " must be <= 100%, got " + r.num() + "/" + r.den());
            return false;
        }
        return true;
    }

    /** 比较两个正比例，用 BigInteger 交叉相乘，避免溢出。 */
    static int compare(Ratio a, Ratio b) {
        return BigInteger.valueOf(a.num()).multiply(BigInteger.valueOf(b.den()))
                .compareTo(BigInteger.valueOf(b.num()).multiply(BigInteger.valueOf(a.den())));
    }

    private boolean amount(String what, long value) {
        if (value <= 0 || value > MAX_AMOUNT) {
            fail(what + " must be in 1.." + MAX_AMOUNT + ", got " + value);
            return false;
        }
        return true;
    }

    private void count(String what, int value, int min, int max) {
        if (value < min || value > max) {
            fail(what + " must be in " + min + ".." + max + ", got " + value);
        }
    }

    private void duration(String what, long ms) {
        if (ms <= 0 || ms > MAX_DURATION_MS) {
            fail(what + " must be in 1.." + MAX_DURATION_MS + ", got " + ms);
        }
    }

    private boolean present(Object value, String what) {
        if (value == null) {
            fail(what + " missing");
            return false;
        }
        return true;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void fail(String message) {
        errors.add(message);
    }
}
