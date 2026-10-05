package com.millionnaire.engine.core.state;

/**
 * 按观察者生成的视图投影（手牌、危险牙、随机状态等不得出现在他人视图中）。
 * 视图只用于下发，服务端回放一律使用完整事件；不得把 {@link EngineState} 直接当客户端快照。
 */
public interface View<V> {
    V project(EngineState state, String viewerId);
}
