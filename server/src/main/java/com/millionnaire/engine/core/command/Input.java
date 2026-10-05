package com.millionnaire.engine.core.command;

/**
 * 进入引擎的唯一输入：服务端接收序号 seq + 服务端可信接收时间 serverTime + 命令。引擎从不读取系统时钟。
 * <p>输入契约（外层必须严格保序）：
 * <ul>
 *   <li>seq 从 1 开始、逐个连续递增；跳号视为契约违约（{@code InputOrderException}，状态不变）；</li>
 *   <li>seq 等于已处理的最后序号且内容摘要相同：重复投递，忽略（DUPLICATE）；内容不同：契约违约；</li>
 *   <li>seq 小于最后序号：过期重放，忽略（STALE）——引擎不保存历史摘要，幂等结果须由外层按 requestId 记录；</li>
 *   <li>serverTime 低于接收水位 lastReceivedAt：拒绝（TIME_REGRESSION），只消耗序号；</li>
 *   <li>命令中的字符串必须是合法 Unicode（外层解析时拒绝孤立代理字符），否则无法规范编码，引擎抛异常且不改状态。</li>
 * </ul>
 */
public record Input(long seq, long serverTime, Command command) {
    public Input {
        if (command == null) {
            throw new IllegalArgumentException("command missing");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
    }
}
