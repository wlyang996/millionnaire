package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 先于结构实现写下的行为契约；新增类型在旧代码上不存在，不冒称旧版可执行。 */
class M3aStructureTest {
    @Test
    void buyingAppendsTheNextTaskAfterPaymentAndConsumesEachResultOnce() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        var l = t.game().turn().landing();
        assertEquals(List.of(LandingStep.BUY), l.tasks());
        assertEquals(0, l.cursor());
        assertTrue(l.results().isEmpty());
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        l = t.game().turn().landing();
        assertEquals(List.of(LandingStep.BUY, LandingStep.UPGRADE), l.tasks());
        assertEquals(List.of(LandingResult.BOUGHT), l.results());
        assertEquals(1, l.cursor());
        assertEquals(l.chainId(), t.game().turn().chain().chainId());
        t.act(w -> new GameCommand.SkipUpgrade("p1", w));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void debtSuspendsTheRentTaskUntilPaymentAndKeepsItsChainAndLockedPath() {
        Table t = M3aTest.realDebt();
        var d = t.game().debt();
        var l = t.game().turn().landing();
        var chain = t.game().turn().chain();
        assertEquals(DebtPath.MANUAL, d.path());
        assertEquals(l.landingId(), d.source().landingId());
        assertEquals(l.cursor(), d.source().cursor());
        assertEquals(List.of(LandingStep.RENT), l.tasks());
        assertEquals(0, l.cursor());
        assertEquals(1, chain.segments().size());
        assertEquals(List.of(5), chain.segments().get(0).walked());
        t.send(new GameCommand.ConnectionSuspected(1, "p1", 1));
        t.send(new GameCommand.ConnectionConfirmed(1, "p1", 2));
        assertEquals(d.path(), t.game().debt().path());
        assertEquals(chain, t.game().turn().chain());
        t.act(w -> new GameCommand.EmergencyMortgage("p1", w, 1));
        assertNull(t.game().debt());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void geometryPreservesWalkOrderAndDistinguishesBackwardTargetedAndJailRewardRules() {
        assertEquals(List.of(29, 0, 1), MovementRules.segment(1, MoveKind.DICE, 28, 3, 30).walked());
        assertTrue(MovementRules.segment(1, MoveKind.EVENT_FORWARD, 29, 1, 30).startEligible());
        assertFalse(MovementRules.segment(1, MoveKind.EVENT_BACKWARD, 1, 2, 30).startEligible());
        assertEquals(List.of(0, 29), MovementRules.segment(1, MoveKind.EVENT_BACKWARD, 1, 2, 30).walked());
        assertFalse(MovementRules.segment(1, MoveKind.TARGETED, 29, 2, 30).startEligible());
        assertTrue(MovementRules.segment(1, MoveKind.TARGETED, 29, 1, 30).startEligible());
        assertTrue(MovementRules.segment(1, MoveKind.TARGETED, 29, 1, 30).walked().isEmpty());
        assertFalse(MovementRules.jailJump(1, 29, 15, 30).startEligible());
    }
    @Test
    void chainRejectsDisconnectedOrRepeatedSegments() {
        var first = MovementRules.segment(1, MoveKind.DICE, 28, 3, 30);
        var chain = new MoveChain(1, 1, "p1", 28, false, false, List.of(), 0).append(first);
        assertThrows(IllegalStateException.class, () -> chain.append(first));
        assertThrows(IllegalStateException.class,
                () -> chain.append(MovementRules.segment(2, MoveKind.EVENT_FORWARD, 9, 2, 30)));
    }

    @Test
    void redirectionKeepsEventAndRewardMarkersAndTheEntireOrderedWalk() {
        var chain = new MoveChain(1, 1, "p1", 28, false, false, List.of(), 0)
                .append(MovementRules.segment(1, MoveKind.DICE, 28, 3, 30)).rewarded().drewEvent();
        chain = chain.append(MovementRules.segment(2, MoveKind.EVENT_BACKWARD, 1, 2, 30))
                .append(MovementRules.segment(3, MoveKind.EVENT_FORWARD, 29, 1, 30));
        assertEquals(6, chain.walkedSteps());
        assertTrue(chain.startRewardGiven());
        assertTrue(chain.eventDrawn());
        assertEquals(List.of(1, 2, 3), chain.segments().stream().map(MoveSegment::number).toList());
        var finalChain = chain;
        assertThrows(IllegalStateException.class, finalChain::rewarded);
        assertThrows(IllegalStateException.class, finalChain::drewEvent);
    }

    @Test
    void aForcedStopCountsOnlyEnteredTilesAndIgnoresUnwalkedRewards() {
        var planned = MovementRules.segment(1, MoveKind.DICE, 28, 4, 30);
        var stopped = MovementRules.stopAt(planned, 1, 30);
        assertEquals(List.of(29), stopped.walked());
        assertEquals(29, stopped.to());
        assertFalse(stopped.startEligible());
        assertTrue(MovementRules.stopAt(planned, 2, 30).startEligible());
        assertFalse(MovementRules.stopAt(MovementRules.segment(1, MoveKind.EVENT_BACKWARD, 1, 4, 30), 1, 30).startEligible());
        var targeted = MovementRules.segment(1, MoveKind.TARGETED, 28, 4, 30);
        assertEquals(targeted, MovementRules.stopAt(targeted, 1, 30));
        var jailed = MovementRules.jailJump(1, 28, 15, 30);
        assertEquals(jailed, MovementRules.stopAt(jailed, 1, 30));
        assertThrows(IllegalArgumentException.class, () -> MovementRules.stopAt(planned, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> MovementRules.stopAt(planned, 5, 30));
        var beforeReward = new MoveChain(1, 1, "p1", 1, false, false, List.of(), 0)
                .append(MovementRules.segment(1, MoveKind.EVENT_BACKWARD, 1, 2, 30));
        assertTrue(beforeReward.canReward(MovementRules.segment(2, MoveKind.EVENT_FORWARD, 29, 1, 30)));
    }

    @Test
    void completingALandingForRedirectionPreservesTheActionChain() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.act(w -> new GameCommand.SkipUpgrade("p1", w));
        int finished = M2bReviewTest.lastIndexOf(t.log, com.millionnaire.engine.core.event.GameEvent.LandingFinished.class);
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log.subList(0, finished));
        var chain = t.game().turn().chain();
        var c = M2bReviewTest.ctx(t);
        EconomyModule.completeLanding(c);
        assertNull(c.state().game().turn().landing());
        assertEquals(chain, c.state().game().turn().chain());
        assertEquals(t.game().turn().turnNo(), c.state().game().turn().turnNo());
        assertEquals(1, c.events().size());
        assertInstanceOf(com.millionnaire.engine.core.event.GameEvent.LandingFinished.class, c.events().getFirst());
        // 这是内部事件之间的状态，M3b 必须继续移动/落点后才可提交输入，不能保存为步边界快照。
    }

    @Test
    void movementAnimationUsesTheValidatedWalkRatherThanCallerMetadata() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        int moved = M2bReviewTest.indexOf(t.log, com.millionnaire.engine.core.event.GameEvent.PlayerMoved.class, 0);
        t.log.subList(moved, t.log.size()).clear();
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log);
        var c = M2bReviewTest.ctx(t);
        // 参数中的几何主字段合法，但附件路径不是演化核验后的真实路径。
        var metadata = new MoveSegment(1, MoveKind.DICE, 0, 1, 1, List.of(), false);
        TurnModule.moveAndLand(c, metadata, 0);
        long expected = c.now() + t.config.timing().animPerStepMs();
        assertEquals(expected, c.state().game().flow().top().orElseThrow().window().opensAt());
        M2bReviewTest.commit(t, c);
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

}
