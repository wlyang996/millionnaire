package com.millionnaire.engine.ledger;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 账本的可信增量校验（M2 P7）。
 * <p>可信的唯一来源是<b>本进程内已完整校验过的账本对象</b>（按对象同一性 {@code ==} 记忆，弱引用、容量有限）。
 * 新账本若在内存中由某个可信账本追加而来——基线与开局余额相同，且其日志前缀与可信账本的日志<b>逐项是同一个分录对象</b>——
 * 就只重放新增分录（{@link Ledger#verifyExtension}）；否则完整重放全部历史。
 * <p>从快照或事件日志解码出的账本由新对象组成，永远不会命中缓存，因而恢复入口与重建入口必然完整校验
 * （另见 {@link #verifyFully}）；record 中的任何字段（包括前缀数据）都不会被当作可信依据。缓存只影响耗时，不影响结果。
 */
public final class TrustedLedgers {
    private static final int CAPACITY = 64;
    private static final ArrayDeque<WeakReference<Ledger>> TRUSTED = new ArrayDeque<>();

    private TrustedLedgers() {
    }

    /** 每步边界使用：命中可信前缀则增量校验，否则完整校验；通过后记为可信。 */
    public static void verify(Ledger ledger) {
        Ledger prefix = trustedPrefix(ledger);
        if (prefix == ledger) {
            return;
        }
        if (prefix != null) {
            ledger.verifyExtension(prefix);
        } else {
            ledger.verifyInvariants();
        }
        remember(ledger);
    }

    /** 恢复与审计入口：无条件完整重放，通过后记为可信。 */
    public static void verifyFully(Ledger ledger) {
        ledger.verifyInvariants();
        remember(ledger);
    }

    /** 清空可信记忆（测试用：比较增量与完整校验的结果）。 */
    public static void forget() {
        synchronized (TRUSTED) {
            TRUSTED.clear();
        }
    }

    private static Ledger trustedPrefix(Ledger ledger) {
        List<Ledger> candidates = new ArrayList<>();
        synchronized (TRUSTED) {
            for (WeakReference<Ledger> ref : TRUSTED) {
                Ledger l = ref.get();
                if (l != null) {
                    candidates.add(l);
                }
            }
        }
        Ledger best = null;
        for (Ledger t : candidates) {
            if (t == ledger) {
                return t;
            }
            if (extendsByIdentity(ledger, t) && (best == null || t.journal().size() > best.journal().size())) {
                best = t;
            }
        }
        return best;
    }

    private static boolean extendsByIdentity(Ledger ledger, Ledger trusted) {
        if (ledger.journal() == null || ledger.journal().size() < trusted.journal().size()
                || ledger.baseline() != trusted.baseline() || ledger.opening() == null
                || !ledger.opening().equals(trusted.opening())) {
            return false;
        }
        for (int i = 0; i < trusted.journal().size(); i++) {
            if (ledger.journal().get(i) != trusted.journal().get(i)) {
                return false;
            }
        }
        return true;
    }

    private static void remember(Ledger ledger) {
        synchronized (TRUSTED) {
            TRUSTED.removeIf(r -> r.get() == null || r.get() == ledger);
            TRUSTED.addFirst(new WeakReference<>(ledger));
            while (TRUSTED.size() > CAPACITY) {
                TRUSTED.removeLast();
            }
        }
    }
}
