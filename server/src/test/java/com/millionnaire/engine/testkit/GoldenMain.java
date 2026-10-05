package com.millionnaire.engine.testkit;

import com.millionnaire.engine.replay.RunResult;
import com.millionnaire.engine.replay.ScenarioRunner;
import com.millionnaire.engine.serialize.Canonical;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在独立 JVM 进程中运行演示场景并输出规范结果（供跨进程一致性测试调用）。
 * 用法：无参数时打印 "状态哈希 快照字节SHA-256 事件日志字节SHA-256"；{@code write <file>} 写出黄金快照文件。
 */
public final class GoldenMain {
    private GoldenMain() {
    }

    public static void main(String[] args) throws IOException {
        ScenarioRunner<DemoState> runner = new ScenarioRunner<>(DemoScenarios.full(), DemoDomain.INSTANCE);
        RunResult r = runner.run();
        String snapshot = runner.engine().snapshot(r.state());
        if (args.length == 2 && args[0].equals("write")) {
            Files.write(Path.of(args[1]), snapshot.getBytes(StandardCharsets.UTF_8));
            return;
        }
        System.out.println(r.finalHash() + " " + Canonical.sha256Hex(snapshot.getBytes(StandardCharsets.UTF_8))
                + " " + Canonical.sha256Hex(runner.engine().encodeEvents(r.events()).getBytes(StandardCharsets.UTF_8)));
    }
}
