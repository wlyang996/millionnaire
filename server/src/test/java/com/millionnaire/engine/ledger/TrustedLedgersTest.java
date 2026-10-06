package com.millionnaire.engine.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** M2 P7：可信增量校验只信任本进程内完整校验过的对象，伪造的余额或历史仍会被发现。 */
class TrustedLedgersTest {

    private static Ledger opened() {
        Map<String, Long> cash = new TreeMap<>();
        cash.put("a", 1000L);
        cash.put("b", 1000L);
        return Ledger.open(cash);
    }

    @Test
    void anInMemoryExtensionOfATrustedLedgerIsVerifiedIncrementally() {
        Ledger base = opened();
        for (int i = 0; i < 50; i++) {
            base = base.transfer("a", "b", 1, "T", null);
        }
        TrustedLedgers.verifyFully(base);
        Ledger next = base.transfer("b", Ledger.SYSTEM, 10, "T", null);
        assertDoesNotThrow(() -> TrustedLedgers.verify(next));
        // 同样的历史、被改动的余额：增量重放发现不一致
        TreeMap<String, Long> cash = new TreeMap<>(next.cash());
        cash.put("a", cash.get("a") + 1);
        cash.put("b", cash.get("b") - 1);
        Ledger forged = new Ledger(next.baseline(), next.opening(), cash, next.frozen(), next.systemNet(), next.journal());
        assertThrows(LedgerException.class, () -> TrustedLedgers.verify(forged));
    }

    @Test
    void aDecodedOrRebuiltLedgerIsNeverTrustedByItsContent() {
        Ledger base = opened().transfer("a", "b", 100, "T", null);
        TrustedLedgers.verifyFully(base);
        // 历史前缀"看起来相同"但不是同一批对象：先篡改第一笔，再追加，余额与篡改后的历史一致 → 这是另一段合法历史，完整重放通过
        List<JournalEntry> journal = new ArrayList<>(base.journal());
        JournalEntry first = journal.get(0);
        journal.set(0, new JournalEntry(first.entryNo(), first.reason(), first.ref(),
                List.of(new Leg("a", -200), new Leg("b", 200))));
        TreeMap<String, Long> cash = new TreeMap<>(base.cash());
        cash.put("a", 800L);
        cash.put("b", 1200L);
        Ledger rewritten = new Ledger(base.baseline(), base.opening(), cash, base.frozen(), base.systemNet(), journal);
        assertDoesNotThrow(() -> TrustedLedgers.verify(rewritten), "a consistent alternative history is valid");
        // 但余额与改写后的历史不一致时，不会因"前缀看起来眼熟"而被放过
        Ledger lying = new Ledger(base.baseline(), base.opening(), base.cash(), base.frozen(), base.systemNet(), journal);
        assertThrows(LedgerException.class, () -> TrustedLedgers.verify(lying));
        // 解码得到的同值账本同样走完整重放
        Ledger decoded = Ledger.restore(com.millionnaire.engine.serialize.Canonical.encode(base));
        assertDoesNotThrow(() -> TrustedLedgers.verify(decoded));
    }
}
