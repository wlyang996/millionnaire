/**
 * 联机协议的服务端数据形状（与 gateway/README.md 一致）。纯 TS，不依赖 cc。
 * 服务端视图（S 前缀）经 ViewAdapter 转成 core/Models 的客户端模型后再给界面用。
 */

export interface SMember {
    playerId: string;
    nickname: string;
    ready: boolean;
}

export interface SSettings {
    boardId: string;
    initialCash: number;
    endMode: 'TIME_LIMIT' | 'BANKRUPTCY';
    timeLimitMinutes: number;
    rollSeconds: number;
}

export interface SPlayer {
    playerId: string;
    position: number;
    cash: number;
    frozen: number;
    handCount: number;
    life: 'ALIVE' | 'BANKRUPT' | 'SURRENDERED';
    inJail: boolean;
    jailFailures: number;
    control: 'MANUAL' | 'AWAY' | 'HOSTED';
    conn: 'ONLINE' | 'SUSPECT' | 'OFFLINE';
}

export interface SOwnable {
    tile: number;
    owner: string | null;
    level: number;
    mortgaged: boolean;
    principal: number;
    lockedBy: string | null;
}

export interface SWindow {
    windowId: number;
    kind: string;
    owner: string;
    opensAt: number;
    deadline: number;
    paused: boolean;
}

export interface SLanding {
    landingId: number;
    tile: number;
    step: string;
    decisionPending: boolean;
}

export interface SDebt {
    debtId: number;
    debtor: string;
    creditor: string | null;
    amount: number;
    segment: number;
    continued: boolean;
    continueAvailable: boolean;
    windowId: number;
}

export interface SGame {
    gameNo: number;
    phase: 'RUNNING' | 'DRAINING';
    players: SPlayer[];
    orderDraws: { playerId: string; draws: number[] }[];
    board: { boardId: string; ownables: SOwnable[] };
    turnNo: number;
    currentPlayer: string | null;
    stage: string;
    globalEndsAt: number;
    windows: SWindow[];
    landing: SLanding | null;
    debt: SDebt | null;
    myHand: string[];
}

export interface SStanding {
    playerId: string;
    rank: number;
    netWorth: number;
    cash: number;
}

export interface SResult {
    gameNo: number;
    reason: string;
    standings: SStanding[];
}

export interface SView {
    roomId: string;
    status: 'OPEN' | 'CLOSED';
    hostId: string | null;
    members: SMember[];
    settings: SSettings;
    game: SGame | null;
    gamesPlayed: number;
    lastResult: SResult | null;
}

/** 本步对你可见的事件：kind 为服务端事件类名（如 DiceRolled、PlayerMoved），data 为其字段。 */
export interface SEvent {
    kind: string;
    data: Record<string, unknown> | null;
}

export interface UpdateMsg {
    type: 'UPDATE';
    roomCode: string;
    version: number;
    serverTime: number;
    events: SEvent[];
    view: SView;
}

export interface ResultMsg {
    type: 'RESULT';
    requestId: string | null;
    ok: boolean;
    /** ACCEPTED / REJECTED（规则拒绝）/ DUPLICATE / STALE / ERROR（网关错误）/ OFFLINE / TIMEOUT（客户端本地） */
    outcome: string;
    code: string | null;
    roomCode?: string;
    message?: string;
    settingsCode?: string;
}

export interface HelloMsg {
    type: 'HELLO';
    userId: string;
    nickname: string;
    serverTime: number;
    roomCode: string | null;
}

export interface BoardTemplateTile {
    index: number;
    type: 'START' | 'PROPERTY' | 'STATION' | 'EVENT' | 'BANK' | 'JAIL' | 'REST' | 'GAME_ZONE';
    tier: 'LOW' | 'MID' | 'HIGH' | null;
    auctionDesignated: boolean;
}

export interface BoardTemplate {
    id: string;
    minPlayers: number;
    maxPlayers: number;
    tiles: BoardTemplateTile[];
}

/** 客户端可发的对局命令（GAME 消息的 command）。 */
export type GameCommandName =
    | 'RollDice' | 'PayBail' | 'DrawEventCard' | 'DiscardCard' | 'BuyProperty' | 'DeclinePurchase'
    | 'StartLandAuction' | 'UpgradeProperty' | 'SkipUpgrade' | 'BankMortgage' | 'Redeem' | 'EmergencyMortgage'
    | 'FinishBank' | 'ContinueDebt' | 'DeclareBankruptcy' | 'ResumeControl' | 'Surrender';
