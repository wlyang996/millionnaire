package com.millionnaire.engine.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.serialize.Canonical;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LedgerTest {
    private static Ledger open() {
        Map<String, Long> initial = new TreeMap<>();
        initial.put("a", 3000L);
        initial.put("b", 3000L);
        initial.put("c", 3000L);
        return Ledger.open(initial);
    }

    @Test
    void openingSetsBaselineAndZeroSystem() {
        Ledger l = open();
        assertEquals(9000, l.baseline());
        assertEquals(0, l.systemNet());
        l.verifyInvariants();
    }

    @Test
    void balancedMultiLegEntryKeepsConservation() {
        // 系统拍卖成交 1239：买家付款，发起人分成 123（向下取整），余量 1116 归系统
        Ledger l = open().post("SYSTEM_AUCTION_SOLD", "flow-1",
                List.of(new Leg("b", -1239), new Leg("a", 123), new Leg(Ledger.SYSTEM, 1116)));
        assertEquals(1761, l.cash("b"));
        assertEquals(3123, l.cash("a"));
        assertEquals(1116, l.systemNet());
        assertEquals(1, l.journal().size());
        l.verifyInvariants();
        Ledger reward = l.transfer(Ledger.SYSTEM, "c", 1000, "START_REWARD", "turn-1");
        assertEquals(116, reward.systemNet());
        reward.verifyInvariants();
    }

    @Test
    void unbalancedOrMalformedEntriesRejected() {
        Ledger l = open();
        assertThrows(LedgerException.class,
                () -> l.post("X", null, List.of(new Leg("a", -100), new Leg("b", 99))));
        assertThrows(LedgerException.class, () -> l.post("X", null, List.of(new Leg("a", -100))));
        assertThrows(LedgerException.class,
                () -> l.post("X", null, List.of(new Leg("a", -100), new Leg("a", 100))));
        assertThrows(LedgerException.class,
                () -> l.post("X", null, List.of(new Leg("a", 0), new Leg("b", 0))));
        assertThrows(LedgerException.class,
                () -> l.post("X", null, List.of(new Leg("a", -100), new Leg("zz", 100))));
        assertThrows(LedgerException.class,
                () -> l.post(" ", null, List.of(new Leg("a", -100), new Leg("b", 100))));
        assertThrows(ArithmeticException.class,
                () -> l.post("X", null, List.of(new Leg(Ledger.SYSTEM, Long.MAX_VALUE), new Leg("a", Long.MAX_VALUE),
                        new Leg("b", Long.MIN_VALUE))));
    }

    @Test
    void cannotOverdraftOrSpendFrozenCash() {
        Ledger l = open().freeze("a", 2500);
        assertEquals(3000, l.cash("a"));
        assertEquals(500, l.available("a"));
        assertThrows(LedgerException.class, () -> l.transfer("a", "b", 501, "RENT", null));
        Ledger paid = l.transfer("a", "b", 500, "RENT", null);
        assertEquals(0, paid.available("a"));
        assertThrows(LedgerException.class, () -> open().transfer("a", "b", 3001, "RENT", null));
        assertThrows(LedgerException.class, () -> l.freeze("a", 501));
        assertThrows(LedgerException.class, () -> l.unfreeze("a", 2501));
        // 结算冻结报价：先解冻，再扣款
        Ledger settled = l.transact(x -> x.unfreeze("a", 2500).transfer("a", "c", 2500, "AUCTION_SOLD", "flow-2"));
        assertEquals(500, settled.cash("a"));
        assertEquals(0, settled.frozen("a"));
    }

    @Test
    void transactionIsAllOrNothing() {
        Ledger l = open();
        assertThrows(LedgerException.class, () -> l.transact(x -> x
                .transfer("a", "b", 1000, "OK", null)
                .transfer("a", "b", 5000, "TOO_MUCH", null)));
        assertEquals(3000, l.cash("a"));
        assertEquals(0, l.journal().size());
    }

    @Test
    void invariantCheckDetectsCorruption() {
        Ledger l = open().transfer("a", "b", 100, "RENT", null);
        Map<String, Long> cash = new TreeMap<>(l.cash());
        cash.put("b", cash.get("b") + 1);
        Ledger forged = new Ledger(l.baseline(), l.opening(), cash, l.frozen(), l.systemNet() - 1, l.journal());
        assertThrows(LedgerException.class, forged::verifyInvariants, "conserved total but not matching journal");
        Ledger negative = new Ledger(l.baseline(), l.opening(), l.cash(), Map.of("a", 5000L, "b", 0L, "c", 0L),
                l.systemNet(), l.journal());
        assertThrows(LedgerException.class, negative::verifyInvariants);
        assertThrows(LedgerException.class, () -> l.transact(x -> forged));
    }

    @Test
    void roundTripsCanonically() {
        Ledger l = open().freeze("c", 10).transfer("a", Ledger.SYSTEM, 500, "BAIL", "turn-3");
        Ledger back = Canonical.decode(Canonical.encode(l), Ledger.class);
        assertEquals(l, back);
        back.verifyInvariants();
        assertSame(Ledger.class, back.getClass());
        assertEquals(l, Ledger.restore(Canonical.encode(l)));
    }

    @Test
    void historyIsReplayedEntryByEntry() {
        Map<String, Long> initial = new TreeMap<>(Map.of("a", 100L, "b", 100L));
        Map<String, Long> zero = Map.of("a", 0L, "b", 0L);
        JournalEntry ok = new JournalEntry(1, "RENT", null, List.of(new Leg("a", -10), new Leg("b", 10)));
        Map<String, Long> after = Map.of("a", 90L, "b", 110L);
        new Ledger(200, initial, after, zero, 0, List.of(ok)).verifyInvariants();

        // 中途透支、最终余额却复原
        assertHistoryRejected(initial, initial, List.of(
                new JournalEntry(1, "X", null, List.of(new Leg("a", -150), new Leg("b", 150))),
                new JournalEntry(2, "Y", null, List.of(new Leg("b", -150), new Leg("a", 150)))), "overdraft");
        // 编号不连续
        assertHistoryRejected(initial, after, List.of(
                new JournalEntry(2, "RENT", null, ok.legs())), "number");
        // 空原因
        assertHistoryRejected(initial, after, List.of(
                new JournalEntry(1, " ", null, ok.legs())), "reason");
        // 单腿零额
        assertHistoryRejected(initial, initial, List.of(
                new JournalEntry(1, "Z", null, List.of(new Leg("a", 0)))), "two legs");
        // 未知账户
        assertHistoryRejected(initial, after, List.of(
                new JournalEntry(1, "RENT", null, List.of(new Leg("a", -10), new Leg("ghost", 10)))), "unknown account");
        // 基线与开局余额不符
        Ledger badBaseline = new Ledger(999, initial, initial, zero, 0, List.of());
        assertThrows(LedgerException.class, badBaseline::verifyInvariants);
    }

    @Test
    void transactMustExtendTheSameLedger() {
        Ledger l = open().transfer("a", "b", 100, "RENT", "t1");
        // 回调返回一个重新开局的合法账本：基线一致但历史前缀丢失
        Map<String, Long> current = new TreeMap<>(l.cash());
        assertThrows(LedgerException.class, () -> l.transact(x -> Ledger.open(current)));
        // 回调改写已有历史
        assertThrows(LedgerException.class, () -> l.transact(x -> new Ledger(x.baseline(), x.opening(), x.cash(),
                x.frozen(), x.systemNet(), List.of(new JournalEntry(1, "OTHER", "t1", x.journal().get(0).legs())))));
        Ledger extended = l.transact(x -> x.transfer("b", "c", 50, "RENT", "t2"));
        assertEquals(2, extended.journal().size());
    }

    @Test
    void restoreRejectsForgedHistory() {
        Ledger l = open().transfer("a", "b", 100, "RENT", "t1");
        String text = Canonical.encode(l).replace("\"delta\":-100", "\"delta\":-99");
        assertThrows(LedgerException.class, () -> Ledger.restore(text));
    }

    private static void assertHistoryRejected(Map<String, Long> opening, Map<String, Long> cash, List<JournalEntry> journal,
                                              String fragment) {
        Ledger forged = new Ledger(200, opening, cash, Map.of("a", 0L, "b", 0L), 0, journal);
        LedgerException e = assertThrows(LedgerException.class, forged::verifyInvariants);
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
