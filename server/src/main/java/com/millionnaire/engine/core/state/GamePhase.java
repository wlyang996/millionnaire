package com.millionnaire.engine.core.state;

/** 对局阶段：全局到时后进入 DRAINING，不再启动新回合，完成已获准的结算后结束。 */
public enum GamePhase {
    RUNNING, DRAINING
}
