package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.*;

/** Personal cash transfers, never pooled ownership or monthly gameplay income. */
final class TeamAidModule {
    private TeamAidModule() { }
    static boolean rescueAvailable(RuleConfig rules, GameState g, String debtor) {
        return g.team() != null && rules.team().aidEnabled() && rules.team().debtAidEnabled()
                && g.alive().stream().anyMatch(p -> !p.playerId().equals(debtor) && g.team().sameTeam(debtor, p.playerId()));
    }
    static RejectionCode rejection(SessionState s, GameCommand c, RuleConfig rules, long now) {
        if (!s.inGame()) return RejectionCode.NOT_IN_GAME;
        var g = s.game(); var cfg = rules.team();
        if (g.team() == null) return RejectionCode.NOT_ALLOWED;
        long gameNo = c instanceof GameCommand.TeamAid x ? x.gameNo() : ((GameCommand.RequestTeamAid)c).gameNo();
        long window = c instanceof GameCommand.TeamAid x ? x.windowId() : ((GameCommand.RequestTeamAid)c).windowId();
        long debtId = c instanceof GameCommand.TeamAid x ? x.debtId() : ((GameCommand.RequestTeamAid)c).debtId();
        if (gameNo != g.gameNo()) return RejectionCode.GAME_MISMATCH;
        var p = g.player(c.actor()).orElse(null);
        if (p == null || !p.alive()) return RejectionCode.NOT_MEMBER;
        if (debtId != 0) {
            var d = g.debt();
            if (!cfg.aidEnabled() || !cfg.debtAidEnabled() || d == null || d.debtId() != debtId || d.segment() != 0) return RejectionCode.NOT_ALLOWED;
            if (d.windowId() != window) return RejectionCode.WINDOW_MISMATCH;
            var why = FlowCoordinator.checkWindow(g.flow(), window, d.debtor(), now);
            if (why != null) return why;
            if (c instanceof GameCommand.RequestTeamAid) {
                if (!d.debtor().equals(c.actor())) return RejectionCode.NOT_YOUR_WINDOW;
                return d.aidRequested() ? RejectionCode.ALREADY_REQUESTED : null;
            }
            var x = (GameCommand.TeamAid)c;
            if (!d.aidRequested() || !d.debtor().equals(x.recipient())) return RejectionCode.NOT_ALLOWED;
            if (x.amount() > d.amount() - g.ledger().available(d.debtor())) return RejectionCode.AID_EXCEEDS_GAP;
        } else {
            if (!(c instanceof GameCommand.TeamAid) || !cfg.aidEnabled() || g.debt() != null) return RejectionCode.NOT_ALLOWED;
            if (!c.actor().equals(g.turn().currentPlayer())) return RejectionCode.NOT_YOUR_TURN;
            if (!g.turn().stage().beforeRoll() || g.flow().frames().size() != 1 || g.flow().top().orElseThrow().kind() != FlowKind.TURN)
                return RejectionCode.WRONG_STAGE;
            var why = FlowCoordinator.checkWindow(g.flow(), window, c.actor(), now);
            if (why != null) return why;
            if (g.team().aidCount(c.actor(), g.turn().turnNo()) >= cfg.aidPerTurn()) return RejectionCode.AID_LIMIT;
        }
        var x = (GameCommand.TeamAid)c;
        if (x.recipient() == null || c.actor().equals(x.recipient()) || !g.team().sameTeam(c.actor(), x.recipient())
                || !g.player(x.recipient()).map(PlayerState::alive).orElse(false)) return RejectionCode.NOT_ALLOWED;
        if (x.amount() <= 0 || x.amount() > cfg.aidMaxAmount()) return RejectionCode.INVALID_ARGUMENT;
        return g.ledger().available(c.actor()) < x.amount() ? RejectionCode.INSUFFICIENT_CASH : null;
    }
    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand c) {
        var why = rejection(ctx.state(), c, ctx.config(), ctx.now());
        if (why != null) return why;
        if (c instanceof GameCommand.TeamAid x) {
            ctx.emit(new GameEvent.TeamAidTransferred(x.actor(), x.gameNo(), x.windowId(), x.recipient(), x.amount(), x.debtId(), ctx.now()));
            EconomyModule.payDebtIfReady(ctx);
        } else {
            var x = (GameCommand.RequestTeamAid)c;
            ctx.emit(new GameEvent.TeamAidRequested(x.actor(), x.gameNo(), x.windowId(), x.debtId(), ctx.now()));
        }
        return null;
    }
    static SessionState evolve(SessionState s, GameEvent event, RuleConfig rules) {
        GameCommand c; long at;
        if (event instanceof GameEvent.TeamAidTransferred x) {
            c = new GameCommand.TeamAid(x.actor(), x.gameNo(), x.windowId(), x.recipient(), x.amount(), x.debtId()); at = x.at();
        } else {
            var x = (GameEvent.TeamAidRequested)event;
            c = new GameCommand.RequestTeamAid(x.actor(), x.gameNo(), x.windowId(), x.debtId()); at = x.at();
        }
        LobbyModule.check(c.equals(s.teamSource()) && rejection(s, c, rules, at) == null, "aid requires matching accepted input");
        var g = s.game(); var team = g.team();
        if (c instanceof GameCommand.TeamAid x) {
            g = g.withLedger(g.ledger().transfer(x.actor(), x.recipient(), x.amount(), "TEAM_AID", "team-aid-" + g.turn().turnNo()));
            if (x.debtId() == 0) team = team.usedAid(x.actor(), g.turn().turnNo());
            g = g.withTeam(team);
        } else g = g.withDebt(g.debt().requestedAid());
        return s.withTeamSource(null).withTeam(team).withGame(g);
    }
}
