package com.millionnaire.engine.core.command;

/** 仅推进时间的系统输入，用于外层按 nextWakeUp 唤醒引擎。 */
public record Tick() implements SystemCommand {
}
