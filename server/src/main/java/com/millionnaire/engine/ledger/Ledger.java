package com.millionnaire.engine.ledger;

import com.millionnaire.engine.money.Money;
import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * 资金账本骨架（opus-analysis 4.9）：不可变，可规范序列化。
 * <ul>
 *   <li>账户：系统账户 {@link #SYSTEM}（净额可为负，开局为 0）与各玩家；</li>
 *   <li>现金含冻结：可用 = cash − frozen；冻结不是转账，只是占用标记（不入日志）；</li>
 *   <li>守恒基线：Σ玩家现金 + 系统净额 ≡ 开局现金总额；</li>
 *   <li>每笔分录必须平衡、编号连续、腿非零且账户唯一且存在，任何一步都不得使玩家余额为负；</li>
 *   <li>事务边界用 {@link #transact} 包裹：结果必须保留原基线、开局余额与历史前缀，再执行 {@link #verifyInvariants()}；</li>
 *   <li>恢复入口 {@link #restore} 逐笔重放校验全部历史。</li>
 * </ul>
 */
public record Ledger(
        long baseline,
        Map<String, Long> opening,
        Map<String, Long> cash,
        Map<String, Long> frozen,
        long systemNet,
        List<JournalEntry> journal) {

    public static final String SYSTEM = "$SYSTEM";

    public Ledger {
        opening = Immutable.sortedMap(opening);
        cash = Immutable.sortedMap(cash);
        frozen = Immutable.sortedMap(frozen);
        journal = Immutable.list(journal);
    }

    /** 开局：初始现金作为守恒基线，系统净额为 0，日志为空。 */
    public static Ledger open(Map<String, Long> initialCash) {
        long total = 0;
        TreeMap<String, Long> frozen = new TreeMap<>();
        for (Map.Entry<String, Long> e : initialCash.entrySet()) {
            if (!validPlayerAccount(e.getKey()) || e.getValue() == null) {
                throw new LedgerException("invalid player account " + e.getKey());
            }
            total = Money.add(total, Money.requireNonNegative(e.getValue(), "initial cash"));
            frozen.put(e.getKey(), 0L);
        }
        return new Ledger(total, initialCash, initialCash, frozen, 0, List.of());
    }

    /** 从规范文本恢复，并完整校验历史。 */
    public static Ledger restore(String text) {
        Ledger l = Canonical.decode(text, Ledger.class);
        l.verifyInvariants();
        return l;
    }

    public long cash(String player) {
        return requirePlayer(player);
    }

    public long frozen(String player) {
        requirePlayer(player);
        return frozen.get(player);
    }

    public long available(String player) {
        return cash(player) - frozen(player);
    }

    /** 记一笔平衡分录；玩家支出不得动用冻结部分。 */
    public Ledger post(String reason, String ref, List<Leg> legs) {
        JournalEntry entry = new JournalEntry(journal.size() + 1L, reason, ref, legs);
        TreeMap<String, Long> balances = new TreeMap<>(cash);
        long newSystem = apply(balances, systemNet, entry, journal.size() + 1L);
        for (Leg leg : entry.legs()) {
            if (!leg.account().equals(SYSTEM) && balances.get(leg.account()) < frozen.get(leg.account())) {
                throw new LedgerException("insufficient available cash for " + leg.account());
            }
        }
        return new Ledger(baseline, opening, balances, frozen, newSystem, Immutable.append(journal, entry));
    }

    /** 两方转账的便捷写法。 */
    public Ledger transfer(String from, String to, long amount, String reason, String ref) {
        Money.requirePositive(amount, "amount");
        return post(reason, ref, List.of(new Leg(from, -amount), new Leg(to, amount)));
    }

    public Ledger freeze(String player, long amount) {
        Money.requirePositive(amount, "freeze amount");
        if (amount > available(player)) {
            throw new LedgerException("cannot freeze more than available cash for " + player);
        }
        return withFrozen(player, frozen.get(player) + amount);
    }

    public Ledger unfreeze(String player, long amount) {
        Money.requirePositive(amount, "unfreeze amount");
        if (amount > frozen(player)) {
            throw new LedgerException("cannot unfreeze more than frozen for " + player);
        }
        return withFrozen(player, frozen.get(player) - amount);
    }

    /**
     * 事务：在副本上执行一组操作，提交前校验——结果必须由本账本演化而来（同基线、同开局余额、
     * 历史以本账本日志为前缀），且满足全部不变量。任何失败都不影响原账本。
     */
    public Ledger transact(UnaryOperator<Ledger> operations) {
        Ledger result = operations.apply(this);
        if (result.baseline != baseline || !result.opening.equals(opening)
                || result.journal.size() < journal.size()
                || !result.journal.subList(0, journal.size()).equals(journal)) {
            throw new LedgerException("transaction result does not extend this ledger");
        }
        result.verifyInvariants();
        return result;
    }

    /**
     * 不变量：账户集合一致、开局余额合法且合计为基线、逐笔重放全部分录（形状、编号连续、账户存在、
     * 平衡、每步无负余额）并与当前余额一致、0 ≤ 冻结 ≤ 现金、守恒。
     */
    public void verifyInvariants() {
        if (opening == null || cash == null || frozen == null || journal == null) {
            throw new LedgerException("ledger fields missing");
        }
        if (!opening.keySet().equals(cash.keySet()) || !frozen.keySet().equals(cash.keySet())) {
            throw new LedgerException("account sets differ");
        }
        for (String account : cash.keySet()) {
            if (!validPlayerAccount(account)) {
                throw new LedgerException("invalid player account " + account);
            }
        }
        long openingTotal = 0;
        for (Map.Entry<String, Long> e : opening.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                throw new LedgerException("invalid opening balance for " + e.getKey());
            }
            openingTotal = Money.add(openingTotal, e.getValue());
        }
        if (openingTotal != baseline) {
            throw new LedgerException("baseline " + baseline + " != opening total " + openingTotal);
        }
        TreeMap<String, Long> replay = new TreeMap<>(opening);
        long system = 0;
        for (int i = 0; i < journal.size(); i++) {
            system = apply(replay, system, journal.get(i), i + 1L);
        }
        if (!replay.equals(new TreeMap<>(cash)) || system != systemNet) {
            throw new LedgerException("balances do not match journal replay");
        }
        long total = systemNet;
        for (Map.Entry<String, Long> e : cash.entrySet()) {
            Long f = frozen.get(e.getKey());
            if (f == null || f < 0 || f > e.getValue()) {
                throw new LedgerException("frozen out of range for " + e.getKey());
            }
            total = Money.add(total, e.getValue());
        }
        if (total != baseline) {
            throw new LedgerException("conservation broken: total " + total + " != baseline " + baseline);
        }
    }

    /**
     * 增量校验（M2 P7）：以一个<b>已在本进程内完整校验过</b>的账本为前缀（由 {@link TrustedLedgers} 按对象同一性确认，
     * 不信任 record 自带的任何数据），只重放新增分录，结果与当前余额、系统净额一致，并满足冻结与守恒不变量。
     */
    void verifyExtension(Ledger trustedPrefix) {
        if (opening == null || cash == null || frozen == null || journal == null
                || baseline != trustedPrefix.baseline || !opening.equals(trustedPrefix.opening)
                || !opening.keySet().equals(cash.keySet()) || !frozen.keySet().equals(cash.keySet())) {
            throw new LedgerException("ledger does not extend the trusted prefix");
        }
        TreeMap<String, Long> replay = new TreeMap<>(trustedPrefix.cash);
        long system = trustedPrefix.systemNet;
        for (int i = trustedPrefix.journal.size(); i < journal.size(); i++) {
            system = apply(replay, system, journal.get(i), i + 1L);
        }
        if (!replay.equals(new TreeMap<>(cash)) || system != systemNet) {
            throw new LedgerException("balances do not match journal replay");
        }
        long total = systemNet;
        for (Map.Entry<String, Long> e : cash.entrySet()) {
            Long f = frozen.get(e.getKey());
            if (f == null || f < 0 || f > e.getValue()) {
                throw new LedgerException("frozen out of range for " + e.getKey());
            }
            total = Money.add(total, e.getValue());
        }
        if (total != baseline) {
            throw new LedgerException("conservation broken: total " + total + " != baseline " + baseline);
        }
    }

    /**
     * 轻量不变量（每步提交边界使用）：账户集合一致、非负、0 ≤ 冻结 ≤ 现金、守恒、日志编号连续。
     * 不重放历史；完整校验用 {@link #verifyInvariants()}（恢复与审计入口）。
     */
    public void verifyBalances() {
        if (opening == null || cash == null || frozen == null || journal == null
                || !frozen.keySet().equals(cash.keySet()) || !opening.keySet().equals(cash.keySet())) {
            throw new LedgerException("account sets differ");
        }
        long total = systemNet;
        for (Map.Entry<String, Long> e : cash.entrySet()) {
            Long f = frozen.get(e.getKey());
            if (!validPlayerAccount(e.getKey()) || e.getValue() == null || e.getValue() < 0 || f == null || f < 0
                    || f > e.getValue()) {
                throw new LedgerException("balance out of range for " + e.getKey());
            }
            total = Money.add(total, e.getValue());
        }
        if (total != baseline) {
            throw new LedgerException("conservation broken: total " + total + " != baseline " + baseline);
        }
        if (!journal.isEmpty() && journal.get(journal.size() - 1).entryNo() != journal.size()) {
            throw new LedgerException("journal numbering broken");
        }
    }

    /** 校验并应用一笔分录到余额表，返回新的系统净额。 */
    private static long apply(TreeMap<String, Long> balances, long system, JournalEntry entry, long expectedNo) {
        if (entry == null || entry.entryNo() != expectedNo) {
            throw new LedgerException("journal entry number must be " + expectedNo);
        }
        if (entry.reason() == null || entry.reason().isBlank()) {
            throw new LedgerException("entry " + expectedNo + ": reason required");
        }
        List<Leg> legs = entry.legs();
        if (legs == null || legs.size() < 2) {
            throw new LedgerException("entry " + expectedNo + ": needs at least two legs");
        }
        TreeSet<String> seen = new TreeSet<>();
        long sum = 0;
        for (Leg leg : legs) {
            if (leg == null || leg.account() == null || leg.delta() == 0) {
                throw new LedgerException("entry " + expectedNo + ": legs must name an account and move a non-zero amount");
            }
            if (!seen.add(leg.account())) {
                throw new LedgerException("entry " + expectedNo + ": account appears twice: " + leg.account());
            }
            sum = Money.add(sum, leg.delta());
            if (leg.account().equals(SYSTEM)) {
                system = Money.add(system, leg.delta());
            } else {
                Long before = balances.get(leg.account());
                if (before == null) {
                    throw new LedgerException("entry " + expectedNo + ": unknown account " + leg.account());
                }
                long after = Money.add(before, leg.delta());
                if (after < 0) {
                    throw new LedgerException("entry " + expectedNo + ": overdraft on " + leg.account());
                }
                balances.put(leg.account(), after);
            }
        }
        if (sum != 0) {
            throw new LedgerException("entry " + expectedNo + ": unbalanced, legs sum to " + sum);
        }
        return system;
    }

    /** 玩家账户名：非空白，且不是系统账户（open 与恢复共用）。 */
    public static boolean validPlayerAccount(String name) {
        return name != null && !name.isBlank() && !name.equals(SYSTEM);
    }

    private Ledger withFrozen(String player, long value) {
        TreeMap<String, Long> f = new TreeMap<>(frozen);
        f.put(player, value);
        return new Ledger(baseline, opening, cash, f, systemNet, journal);
    }

    private long requirePlayer(String player) {
        Long c = cash.get(player);
        if (c == null) {
            throw new LedgerException("unknown account " + player);
        }
        return c;
    }
}
