package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import java.util.ArrayList;
import java.util.List;

/** Event-square effects. Every result is bound to one cursor; every random field is recomputed from audited draws. */
final class EventModule {
    private EventModule() { }
    static int totalWeight(RuleConfig c) {
        int total = 0;
        for (EventKind kind : EventKind.values()) { total = Math.addExact(total, c.eventWeights().get(kind)); }
        return total;
    }
    static EventKind pick(RuleConfig c, int value) {
        if (value < 0 || value >= totalWeight(c)) { throw new IllegalStateException("event draw out of range"); }
        int cumulative = 0;
        for (EventKind kind : EventKind.values()) {
            cumulative = Math.addExact(cumulative, c.eventWeights().get(kind));
            if (value < cumulative) { return kind; }
        }
        throw new IllegalStateException("event draw out of range");
    }
    static int cashBound(RuleConfig c) {
        return Math.toIntExact((c.economy().eventCashMax() - c.economy().eventCashMin()) / c.economy().eventCashStep() + 1);
    }
    static long cash(RuleConfig c, int value) {
        return Math.addExact(c.economy().eventCashMin(), Math.multiplyExact((long) value, c.economy().eventCashStep()));
    }
    static LandingResult drawnResult(EventKind kind) {
        return switch (kind) {
            case CASH_REWARD -> LandingResult.DRAW_REWARD;
            case CASH_FINE -> LandingResult.DRAW_FINE;
            case CARD -> LandingResult.DRAW_CARD;
            case MOVE -> LandingResult.DRAW_MOVE;
            case JAIL -> LandingResult.DRAW_JAIL;
        };
    }
    static LandingStep effect(EventKind kind) {
        return switch (kind) {
            case CASH_REWARD -> LandingStep.REWARD;
            case CASH_FINE -> LandingStep.FINE;
            case CARD -> LandingStep.CARD;
            case MOVE -> LandingStep.MOVE;
            case JAIL -> LandingStep.TO_JAIL;
        };
    }
    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand c) {
        long window = c instanceof GameCommand.DrawEventCard d ? d.windowId() : ((GameCommand.DiscardCard) c).windowId();
        RejectionCode why = TurnModule.checkTurnWindow(ctx, c.actor(), window);
        if (why != null) { return why; }
        if (!StageTable.rule(StageTable.turnPoint(ctx.state().game())).allows(c)) { return RejectionCode.WRONG_STAGE; }
        if (c instanceof GameCommand.DiscardCard d) {
            var hand = ctx.state().game().player(c.actor()).orElseThrow().hand();
            if (d.index() < 0 || d.index() >= hand.size()) { return RejectionCode.INVALID_ARGUMENT; }
            TurnModule.closeDecision(ctx);
            discard(ctx, d.index(), false);
        } else {
            TurnModule.closeDecision(ctx);
            draw(ctx, false);
        }
        return null;
    }
    static void draw(DecisionContext<SessionState> ctx, boolean auto) {
        var c = ctx.config();
        EventKind kind = pick(c, ctx.draw(DrawPoint.EVENT_KIND, totalWeight(c)));
        long amount = 0;
        MoveKind move = null;
        int distance = 0;
        // Protocol order: kind, then cash OR direction+distance; EVENT_CARD is consumed by receipt.
        if (kind == EventKind.CASH_REWARD || kind == EventKind.CASH_FINE) {
            amount = cash(c, ctx.draw(DrawPoint.EVENT_CASH, cashBound(c)));
        } else if (kind == EventKind.MOVE) {
            move = ctx.draw(DrawPoint.EVENT_MOVE_DIRECTION, 2) == 0 ? MoveKind.EVENT_FORWARD : MoveKind.EVENT_BACKWARD;
            int bound = c.economy().eventMoveMaxSteps() - c.economy().eventMoveMinSteps() + 1;
            distance = c.economy().eventMoveMinSteps() + ctx.draw(DrawPoint.EVENT_MOVE_DISTANCE, bound);
        }
        var g = ctx.state().game();
        var l = g.turn().landing();
        ctx.emit(new GameEvent.EventDrawn(g.turn().currentPlayer(), l.landingId(), l.cursor(), kind, amount, move, distance, auto));
        EconomyModule.resumeLanding(ctx, 0);
    }
    static void performEffect(DecisionContext<SessionState> ctx) {
        var g = ctx.state().game();
        var l = g.turn().landing();
        String player = g.turn().currentPlayer();
        switch (l.currentTask()) {
            case REWARD -> {
                ctx.emit(new GameEvent.EventRewardPaid(player, l.landingId(), l.cursor(), l.event().amount()));
                EconomyModule.resumeLanding(ctx, 0);
            }
            case FINE -> EconomyModule.chargeFee(ctx,
                    new FeeSource(FeeSource.Kind.FINE, l.landingId(), l.cursor(), l.tile(), null, l.event().amount()), 0);
            case CARD -> {
                var card = CardDeck.pick(ctx.config(), ctx.draw(DrawPoint.EVENT_CARD, CardDeck.totalWeight(ctx.config())));
                ctx.emit(new GameEvent.EventCardReceived(player, l.landingId(), l.cursor(), card));
                ctx.emit(new GameEvent.EventHandCount(player, l.landingId(), l.cursor(), ctx.state().game().player(player).orElseThrow().hand().size(), false));
                EconomyModule.resumeLanding(ctx, 0);
            }
            case MOVE, TO_JAIL -> {
                MoveKind kind = l.currentTask() == LandingStep.TO_JAIL ? MoveKind.TO_JAIL : l.event().moveKind();
                int distance = kind == MoveKind.TO_JAIL ? 0 : l.event().distance();
                ctx.emit(new GameEvent.EventMoveCommitted(player, l.landingId(), l.cursor(), kind, distance));
                EconomyModule.completeLanding(ctx);
                var chain = ctx.state().game().turn().chain();
                var board = LobbyModule.board(ctx.config(), g.settings());
                var segment = kind == MoveKind.TO_JAIL
                        ? MovementRules.jailJump(chain.segments().size() + 1, l.tile(), TurnModule.jailIndex(board), board.size())
                        : MovementRules.segment(chain.segments().size() + 1, kind, l.tile(), distance, board.size());
                TurnModule.moveAndLand(ctx, segment, 0);
            }
            default -> throw new IllegalStateException("not an event effect " + l.currentTask());
        }
    }
    static void discard(DecisionContext<SessionState> ctx, int index, boolean auto) {
        var g = ctx.state().game();
        var l = g.turn().landing();
        String player = g.turn().currentPlayer();
        var card = g.player(player).orElseThrow().hand().get(index);
        ctx.emit(new GameEvent.EventCardDiscarded(player, l.landingId(), l.cursor(), index, card, auto));
        ctx.emit(new GameEvent.EventHandCount(player, l.landingId(), l.cursor(), ctx.state().game().player(player).orElseThrow().hand().size(), true));
        EconomyModule.resumeLanding(ctx, 0);
    }
    static boolean handles(GameEvent e) {
        return e instanceof GameEvent.EventDrawn || e instanceof GameEvent.EventRewardPaid
                || e instanceof GameEvent.FeeCharged || e instanceof GameEvent.FeePaid || e instanceof GameEvent.EventMoveCommitted
                || e instanceof GameEvent.EventCardReceived || e instanceof GameEvent.EventCardDiscarded || e instanceof GameEvent.EventHandCount;
    }
    static boolean source(GameState g, String player, long id, int cursor, LandingStep task) {
        var l = g.turn().landing();
        return l != null && player != null && player.equals(g.turn().currentPlayer()) && l.landingId() == id
                && l.cursor() == cursor && l.currentTask() == task && g.turn().stage() == TurnStage.LANDING
                && g.flow().frames().isEmpty() && g.debt() == null;
    }
    static GameState evolve(GameState g, GameEvent event, Draws draws, RuleConfig c) {
        var t = g.turn();
        var l = t.landing();
        var k = t.track();
        return switch (event) {
            case GameEvent.EventDrawn e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.EVENT)
                        && l.step() == LandingStep.EVENT && l.decisionOpen() && l.event() == null
                        && t.chain() != null && !t.chain().eventDrawn(), "event draw source mismatch");
                EventKind kind = pick(c, draws.take(DrawPoint.EVENT_KIND, totalWeight(c)));
                LobbyModule.check(e.kind() == kind, "event kind does not match draw");
                long amount = 0;
                MoveKind move = null;
                int distance = 0;
                if (kind == EventKind.CASH_REWARD || kind == EventKind.CASH_FINE) {
                    amount = cash(c, draws.take(DrawPoint.EVENT_CASH, cashBound(c)));
                } else if (kind == EventKind.MOVE) {
                    move = draws.take(DrawPoint.EVENT_MOVE_DIRECTION, 2) == 0 ? MoveKind.EVENT_FORWARD : MoveKind.EVENT_BACKWARD;
                    distance = c.economy().eventMoveMinSteps() + draws.take(DrawPoint.EVENT_MOVE_DISTANCE,
                            c.economy().eventMoveMaxSteps() - c.economy().eventMoveMinSteps() + 1);
                }
                LobbyModule.check(e.amount() == amount, "event amount does not match draw");
                LobbyModule.check(e.moveKind() == move, "event direction does not match draw");
                LobbyModule.check(e.distance() == distance, "event distance does not match draw");
                var result = new EventResolution(kind, amount, move, distance, -1, false);
                var after = g.withTurn(t.withChain(t.chain().drewEvent()));
                yield after.withTurn(after.turn().withLanding(LandingRules.consume(c, after, l.withEvent(result), drawnResult(kind))));
            }
            case GameEvent.EventRewardPaid e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.REWARD)
                        && l.event() != null && l.event().kind() == EventKind.CASH_REWARD && e.amount() == l.event().amount(), "event reward mismatch");
                var paid = g.withLedger(g.ledger().transfer(Ledger.SYSTEM, e.playerId(), e.amount(), "EVENT_REWARD", "landing-" + l.landingId()));
                yield paid.withTurn(t.withLanding(LandingRules.consume(c, paid, l, LandingResult.REWARDED)));
            }
            case GameEvent.FeeCharged e -> {
                LobbyModule.check(e.payer().equals(t.currentPlayer()) && k.pendingCharge() == 0 && g.debt() == null
                        && l != null && l.next() == LandingStep.FINE && FeeRules.valid(c, g, l, e.source()), "system fee charge mismatch");
                yield g.withTurn(t.withLanding(l.withStep(null, e.source().amount()))
                        .withTrack(k.fee(e.source(), EconomyModule.debtPath(c, g, e.payer(), e.source().amount()))));
            }
            case GameEvent.FeePaid e -> {
                LobbyModule.check(e.payer().equals(t.currentPlayer()) && g.debt() == null && e.source() != null
                        && e.source().equals(k.feeSource()) && k.pendingCharge() == e.source().amount(), "system fee payment mismatch");
                var paid = g.withLedger(g.ledger().transfer(e.payer(), FeeRules.account(e.source()), e.source().amount(),
                        FeeRules.rule(e.source().kind()).cause(), "landing-" + l.landingId()));
                yield paid.withTurn(t.withLanding(LandingRules.consume(c, paid, l, LandingResult.PAID)).withTrack(k.charge(0)));
            }
            case GameEvent.EventMoveCommitted e -> {
                LandingStep task = e.kind() == MoveKind.TO_JAIL ? LandingStep.TO_JAIL : LandingStep.MOVE;
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), task) && l.event() != null
                        && (task == LandingStep.TO_JAIL ? l.event().kind() == EventKind.JAIL && e.distance() == 0
                            : l.event().kind() == EventKind.MOVE && e.kind() == l.event().moveKind() && e.distance() == l.event().distance())
                        && k.eventMove() == null, "event relocation source mismatch");
                var credential = new EventMove(e.landingId(), e.cursor(), e.playerId(), e.kind(), e.distance());
                yield g.withTurn(t.withLanding(LandingRules.consume(c, g, l, LandingResult.MOVED)).withTrack(k.redirect(credential)));
            }
            case GameEvent.EventCardReceived e -> {
                LobbyModule.check(source(g, e.recipient(), e.landingId(), e.cursor(), LandingStep.CARD)
                        && l.event() != null && l.event().kind() == EventKind.CARD && l.event().newCardIndex() == -1, "event card source mismatch");
                int cardDraw = draws.take(DrawPoint.EVENT_CARD, CardDeck.totalWeight(c));
                CardType card = CardDeck.pick(c, cardDraw);
                LobbyModule.check(e.card() == card, "event card does not match draw");
                var p = g.player(e.recipient()).orElseThrow();
                LobbyModule.check(p.hand().size() <= c.economy().handLimit(), "cannot receive a second overflow card");
                var hand = new ArrayList<>(p.hand());
                hand.add(card);
                yield g.withPlayer(p.withHand(hand)).withTurn(t.withLanding(l.withEvent(l.event().received(p.hand().size(), cardDraw))));
            }
            case GameEvent.EventCardDiscarded e -> {
                LobbyModule.check(source(g, e.recipient(), e.landingId(), e.cursor(), LandingStep.DISCARD)
                        && l.step() == LandingStep.DISCARD && l.decisionOpen() && l.event() != null
                        && !l.event().countPending(), "event discard source mismatch");
                var p = g.player(e.recipient()).orElseThrow();
                LobbyModule.check(p.hand().size() == c.economy().handLimit() + 1 && e.index() >= 0 && e.index() < p.hand().size()
                        && p.hand().get(e.index()) == e.card(), "event discard index/card mismatch");
                LobbyModule.check(!e.auto() || e.index() == l.event().newCardIndex(), "automatic discard must abandon the new card");
                var hand = new ArrayList<>(p.hand());
                hand.remove(e.index());
                yield g.withPlayer(p.withHand(hand)).withTurn(t.withLanding(l.withEvent(l.event().discarded())));
            }
            case GameEvent.EventHandCount e -> {
                var task = e.discarded() ? LandingStep.DISCARD : LandingStep.CARD;
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), task) && l.event() != null
                        && l.event().countPending() && e.handCount() == g.player(e.playerId()).orElseThrow().hand().size(), "event hand count mismatch");
                var updated = l.withEvent(l.event().countReported());
                yield g.withTurn(t.withLanding(LandingRules.consume(c, g, updated, e.discarded() ? LandingResult.DISCARDED : LandingResult.CARD_RECEIVED)));
            }
            default -> throw new IllegalStateException("not an event-square event");
        };
    }
    static boolean hasKind(LandingState l, EventKind kind) { return l.event() != null && l.event().kind() == kind; }
    static boolean validResolution(RuleConfig c, LandingState l) {
        var r = l.event();
        if (r == null) { return !l.results().stream().anyMatch(x -> x.name().startsWith("DRAW_")); }
        if (r.kind() == null || r.countPending() || !l.results().contains(drawnResult(r.kind()))
                || l.tasks().getFirst() != LandingStep.EVENT) { return false; }
        boolean monetary = r.kind() == EventKind.CASH_REWARD || r.kind() == EventKind.CASH_FINE;
        boolean moving = r.kind() == EventKind.MOVE;
        return (monetary ? r.amount() >= c.economy().eventCashMin() && r.amount() <= c.economy().eventCashMax()
                    && (r.amount() - c.economy().eventCashMin()) % c.economy().eventCashStep() == 0 : r.amount() == 0)
                && (moving ? (r.moveKind() == MoveKind.EVENT_FORWARD || r.moveKind() == MoveKind.EVENT_BACKWARD)
                    && r.distance() >= c.economy().eventMoveMinSteps() && r.distance() <= c.economy().eventMoveMaxSteps()
                    : r.moveKind() == null && r.distance() == 0)
                && (r.kind() == EventKind.CARD ? r.newCardIndex() == c.economy().handLimit()
                        && r.cardDraw() >= 0 && r.cardDraw() < CardDeck.totalWeight(c) : r.newCardIndex() == -1 && r.cardDraw() == -1);
    }
}
