package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.*;

/** The single business/control classification. Exhaustive switch forces each new GameCommand to be classified. */
final class BusinessCommands {
    private BusinessCommands() { }
    enum Kind { BUSINESS, CONTROL, SYSTEM, EXTERNAL }
    static Kind kind(Command command) {
        if (!(command instanceof GameCommand game)) { return Kind.EXTERNAL; }
        return switch (game) {
            case GameCommand.RollDice ignored -> Kind.BUSINESS;
            case GameCommand.PayBail ignored -> Kind.BUSINESS;
            case GameCommand.BuyProperty ignored -> Kind.BUSINESS;
            case GameCommand.DeclinePurchase ignored -> Kind.BUSINESS;
            case GameCommand.StartLandAuction ignored -> Kind.BUSINESS;
            case GameCommand.UpgradeProperty ignored -> Kind.BUSINESS;
            case GameCommand.SkipUpgrade ignored -> Kind.BUSINESS;
            case GameCommand.BankMortgage ignored -> Kind.BUSINESS;
            case GameCommand.Redeem ignored -> Kind.BUSINESS;
            case GameCommand.FinishBank ignored -> Kind.BUSINESS;
            case GameCommand.EmergencyMortgage ignored -> Kind.BUSINESS;
            case GameCommand.ContinueDebt ignored -> Kind.BUSINESS;
            case GameCommand.DeclareBankruptcy ignored -> Kind.BUSINESS;
            case GameCommand.Surrender ignored -> Kind.BUSINESS;
            case GameCommand.DrawEventCard ignored -> Kind.BUSINESS;
            case GameCommand.PickStartCard ignored -> Kind.BUSINESS;
            case GameCommand.DiscardCard ignored -> Kind.BUSINESS;
            case GameCommand.PickTooth ignored -> Kind.BUSINESS;
            case GameCommand.UseCard ignored -> Kind.BUSINESS;
            case GameCommand.RespondCard ignored -> Kind.BUSINESS;
            case GameCommand.RequestAuction ignored -> Kind.BUSINESS;
            case GameCommand.Bid ignored -> Kind.BUSINESS;
            case GameCommand.RequestTrade ignored -> Kind.BUSINESS;
            case GameCommand.AnswerTrade ignored -> Kind.BUSINESS;
            case GameCommand.ResumeControl ignored -> Kind.CONTROL;
            case GameCommand.SetControl ignored -> Kind.SYSTEM;
            case GameCommand.ConnectionSuspected ignored -> Kind.SYSTEM;
            case GameCommand.ConnectionConfirmed ignored -> Kind.SYSTEM;
            case GameCommand.Reconnected ignored -> Kind.SYSTEM;
        };
    }
    /** Called after due tasks, inside the discardable decision workspace. A later rejection rolls this back. */
    static RejectionCode beforeCommand(DecisionContext<SessionState> ctx, Command command) {
        if (kind(command) != Kind.BUSINESS || !ctx.state().inGame()) { return null; }
        var g=ctx.state().game(); var p=g.player(command.actor()).orElse(null);
        if (g.progress().noticeTaskId() != 0) return RejectionCode.WINDOW_NOT_OPEN;
        if (p == null || !p.alive()) { return null; } // command-specific membership/life error remains authoritative
        if (p.conn() == ConnState.OFFLINE) { return RejectionCode.CONTROL_NOT_MANUAL; }
        if (p.control() != ControlMode.MANUAL) {
            ctx.emit(new GameEvent.ControlChanged(p.playerId(),ControlMode.MANUAL));
            TurnModule.policyChanged(ctx,p.playerId());
        }
        return null;
    }
    /** Expected control event for this accepted source, or null if it cannot change control. */
    static GameEvent.ControlChanged expected(SessionState s, Command c) {
        if (!s.inGame()) { return null; }
        var g=s.game();
        if (c instanceof GameCommand.SetControl set) {
            var p=g.player(set.playerId()).orElse(null);
            return set.gameNo()==g.gameNo() && p!=null && set.mode()!=null && p.control()!=set.mode()
                    ? new GameEvent.ControlChanged(set.playerId(),set.mode()) : null;
        }
        var p=c.actor()==null ? null : g.player(c.actor()).orElse(null);
        if (p==null || p.conn()==ConnState.OFFLINE || p.control()==ControlMode.MANUAL) { return null; }
        boolean resume=c instanceof GameCommand.ResumeControl r && r.gameNo()==g.gameNo();
        return resume || kind(c)==Kind.BUSINESS && p.alive() ? new GameEvent.ControlChanged(p.playerId(),ControlMode.MANUAL) : null;
    }
}
