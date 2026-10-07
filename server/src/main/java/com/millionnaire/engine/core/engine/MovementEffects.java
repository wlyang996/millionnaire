package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.MoveKind;
import com.millionnaire.engine.core.state.MovementEffect;
import com.millionnaire.engine.core.state.Roadblock;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.core.state.TurnTrack;
import java.util.TreeSet;

/** Package-private mechanisms only. M4 owns card authority, hand consumption and active-card allowance. */
final class MovementEffects {
    private MovementEffects() { }

    static RejectionCode placeRoadblock(DecisionContext<SessionState> ctx, String player, long window) {
        return perform(ctx, player, window, MovementEffect.Kind.ROADBLOCK, 0);
    }

    static RejectionCode targeted(DecisionContext<SessionState> ctx, String player, long window, int distance) {
        return perform(ctx, player, window, MovementEffect.Kind.TARGETED, distance);
    }

    private static RejectionCode perform(DecisionContext<SessionState> ctx, String player, long window,
                                         MovementEffect.Kind kind, int distance) {
        if (!ctx.state().inGame()) { return RejectionCode.NOT_IN_GAME; }
        var g = ctx.state().game();
        RejectionCode why = rejection(g, ctx.config(), ctx.now(), player, window, kind, distance);
        if (why != null) { return why; }
        var source = new MovementEffect(kind, player, g.turn().turnNo(), window,
                g.player(player).orElseThrow().position(), distance, ctx.now());
        ctx.emit(new GameEvent.MovementEffectCommitted(source));
        if (kind == MovementEffect.Kind.ROADBLOCK) {
            ctx.emit(new GameEvent.RoadblockPlaced(player, source.turnNo(), window,
                    new Roadblock(Math.addExact(g.board().lastRoadblockId(), 1), source.from(), player, source.turnNo())));
        } else {
            TurnModule.closeDecision(ctx);
            ctx.emit(new GameEvent.MoveChainStarted(Math.addExact(g.turn().lastChainId(), 1), source.turnNo(), player, source.from()));
            var board = LobbyModule.board(ctx.config(), g.settings());
            TurnModule.moveAndLand(ctx, MovementRules.segment(1, MoveKind.TARGETED, source.from(), distance, board.size()), 0);
        }
        return null;
    }

    /** One authority for decide and replay. No emission/randomness occurs before all legality checks pass. */
    static RejectionCode rejection(GameState g, RuleConfig c, long now, String player, long window,
                                    MovementEffect.Kind kind, int distance) {
        if (player == null || g.player(player).isEmpty()) { return RejectionCode.NOT_MEMBER; }
        var p = g.player(player).orElseThrow(); var t = g.turn();
        if (!p.alive()) { return RejectionCode.NOT_ALIVE; }
        if (!player.equals(t.currentPlayer())) { return RejectionCode.NOT_YOUR_TURN; }
        if (p.automated()) { return RejectionCode.CONTROL_NOT_MANUAL; }
        if (g.phase() != GamePhase.RUNNING) { return RejectionCode.DRAINING; }
        if (!t.track().equals(TurnTrack.NONE)) { return RejectionCode.WRONG_STAGE; }
        if (kind == null) { return RejectionCode.INVALID_ARGUMENT; }
        if (kind == MovementEffect.Kind.TARGETED && (distance < 1 || distance > 6)
                || kind == MovementEffect.Kind.ROADBLOCK && distance != 0) { return RejectionCode.INVALID_ARGUMENT; }
        // Unsettled landings/overlays are not active-card windows. M4 may add a genuine post-settlement window.
        boolean settled = t.stage() == TurnStage.LANDING && t.landing() == null
                && t.continuation() instanceof Continuation.EndTurn;
        if (g.debt() != null || g.flow().frames().size() != 1 || window != t.windowId()
                || (kind == MovementEffect.Kind.TARGETED ? t.stage() != TurnStage.PRE_ROLL || t.chain() != null
                    : t.stage() != TurnStage.PRE_ROLL && !settled)) { return RejectionCode.WRONG_STAGE; }
        RejectionCode why = FlowCoordinator.checkWindow(g.flow(), window, player, now);
        if (why != null) { return why; }
        var board = LobbyModule.board(c, g.settings());
        if (p.inJail() || board.tiles().get(p.position()).type() == TileType.JAIL && kind == MovementEffect.Kind.ROADBLOCK) {
            return RejectionCode.NOT_ALLOWED;
        }
        if (kind == MovementEffect.Kind.ROADBLOCK && g.board().roadblock(p.position()).isPresent()) {
            return RejectionCode.ALREADY_OWNED;
        }
        if (kind == MovementEffect.Kind.ROADBLOCK && g.board().lastRoadblockId() == Long.MAX_VALUE) {
            return RejectionCode.NOT_ALLOWED;
        }
        return null;
    }

    static GameState commit(GameState g, GameEvent.MovementEffectCommitted e, RuleConfig c) {
        var s = e.source();
        LobbyModule.check(s != null, "missing internal movement source");
        LobbyModule.check(rejection(g, c, s.at(), s.playerId(), s.windowId(), s.kind(), s.distance()) == null,
                "illegal internal movement source stage/player/window/distance");
        LobbyModule.check(s.turnNo() == g.turn().turnNo(), "internal movement source turn mismatch");
        LobbyModule.check(s.from() == g.player(s.playerId()).orElseThrow().position(), "internal movement source position mismatch");
        return g.withTurn(g.turn().withTrack(g.turn().track().effect(s)));
    }

    static GameState place(GameState g, GameEvent.RoadblockPlaced e) {
        var s = g.turn().track().movementEffect();
        LobbyModule.check(s != null && s.kind() == MovementEffect.Kind.ROADBLOCK, "placement without its internal source");
        LobbyModule.check(e.playerId().equals(s.playerId()), "placement player mismatch");
        LobbyModule.check(e.turnNo() == s.turnNo(), "placement turn mismatch");
        LobbyModule.check(e.windowId() == s.windowId(), "placement window mismatch");
        var expected = new Roadblock(Math.addExact(g.board().lastRoadblockId(), 1), s.from(), s.playerId(), s.turnNo());
        LobbyModule.check(expected.equals(e.roadblock()), "placement object/source mismatch");
        return g.withBoard(g.board().placed(e.roadblock())).withTurn(g.turn().withTrack(g.turn().track().effect(null)));
    }

    static GameState trigger(GameState g, GameEvent.RoadblockTriggered e) {
        var t = g.turn(); var due = t.track().roadblockDue(); var chain = t.chain();
        LobbyModule.check(due != null, "trigger without a stopped movement");
        LobbyModule.check(e.playerId().equals(t.currentPlayer()), "trigger player mismatch");
        LobbyModule.check(e.turnNo() == t.turnNo(), "trigger turn mismatch");
        LobbyModule.check(chain != null && e.chainId() == chain.chainId(), "trigger chain mismatch");
        LobbyModule.check(e.segmentNo() == chain.segments().size(), "trigger segment mismatch");
        LobbyModule.check(due.equals(e.roadblock()), "trigger object mismatch");
        return g.withBoard(g.board().triggered(due)).withTurn(t.withTrack(t.track().roadblockConsumed()));
    }

    static void validate(GameState g, RuleConfig c) {
        var b = g.board(); var board = LobbyModule.board(c, g.settings());
        LobbyModule.expect(b.lastRoadblockId() >= 0, "roadblock allocation counter invalid");
        int previous = -1; var ids = new TreeSet<Long>();
        for (Roadblock r : b.roadblocks()) {
            LobbyModule.expect(r != null && r.tile() >= 0 && r.tile() < board.size(), "roadblock tile out of range");
            LobbyModule.expect(r.tile() > previous, "roadblock tiles must be unique and ordered"); previous = r.tile();
            LobbyModule.expect(board.tiles().get(r.tile()).type() != TileType.JAIL, "roadblock on jail tile");
            LobbyModule.expect(r.owner() != null && g.player(r.owner()).isPresent(), "roadblock owner outside permanent roster");
            LobbyModule.expect(r.id() >= 1 && r.id() <= b.lastRoadblockId() && ids.add(r.id()), "roadblock ID invalid or duplicate");
            LobbyModule.expect(r.placedTurn() >= 1 && r.placedTurn() <= g.turn().turnNo(), "roadblock placement turn invalid");
        }
        var chain = g.turn().chain();
        if (chain != null) {
            var consumed = new TreeSet<Long>();
            for (var segment : chain.segments()) {
                var r = segment.stoppedBy();
                if (r == null) { continue; }
                LobbyModule.expect(g.player(r.owner()).isPresent() && r.id() <= b.lastRoadblockId() && consumed.add(r.id())
                        && board.tiles().get(r.tile()).type() != TileType.JAIL, "stopped segment roadblock source invalid");
                LobbyModule.expect(!ids.contains(r.id()), "triggered roadblock still on board");
            }
        }
    }
}
