package com.millionnaire.engine.core.state;

/** 连接状态（open-decisions 已裁决 2）：在线、疑似断线（15 秒）、确认掉线（30 秒）。由外层判定后以系统命令送入。 */
public enum ConnState {
    ONLINE, SUSPECT, OFFLINE
}
