package com.millionnaire.engine.replay;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.random.RandomProtocols;
import com.millionnaire.engine.random.RandomSource;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.RngState;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 随机审计模式：按每个 RandomDrawn 声明的协议，从上一个状态重算一次抽取，核对结果与抽取后状态。
 * 正常重建不做此计算（直接采用记录的状态），因此修改抽取算法不会使旧日志无法重建；审计需要旧协议实现。
 * 还核对所有抽取的协议与创世一致、日志中只有一个创世事件。这只是随机链的一致性检查，不是防篡改证明（日志完整性应另用校验链）。
 */
public final class RandomAudit {
    private RandomAudit() {
    }

    public static void verify(List<Event> events) {
        verify(events, RandomProtocols::forId);
    }

    public static void verify(List<Event> events, Function<String, Optional<RandomSourceFactory>> protocols) {
        if (events.isEmpty() || !(events.get(0) instanceof KernelEvent.Genesis g)) {
            throw new IllegalStateException("event log must start with Genesis");
        }
        RngState rng = g.rng();
        for (int i = 1; i < events.size(); i++) {
            Event e = events.get(i);
            if (e instanceof KernelEvent.Genesis) {
                throw new IllegalStateException("second Genesis at event " + i);
            }
            if (e instanceof KernelEvent.RandomDrawn d) {
                if (!g.rngProtocol().equals(d.protocol())) {
                    throw new IllegalStateException("draw at event " + i + " uses protocol " + d.protocol()
                            + " but the game was created with " + g.rngProtocol());
                }
                RandomSourceFactory factory = protocols.apply(d.protocol())
                        .orElseThrow(() -> new IllegalStateException("unknown random protocol " + d.protocol()));
                RandomSource source = factory.open(rng);
                int value = source.nextInt(d.point(), d.bound());
                if (value != d.value() || !source.state().equals(d.after())) {
                    throw new IllegalStateException("random draw at event " + i + " does not match protocol " + d.protocol());
                }
                rng = d.after();
            }
        }
    }
}
