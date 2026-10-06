package com.millionnaire.engine.core.event;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RngState;
import com.millionnaire.engine.time.ScheduledTask;

/** 内核事件：输入游标、计时、随机与创世，与具体领域无关。 */
public sealed interface KernelEvent extends Event {

    /** 创世：绑定配置哈希、引擎版本、领域、随机协议与初始状态。 */
    record Genesis(String roomId, String configHash, String engineVersion, String domainId, String rngProtocol,
                   RngState rng, long at, DomainState initial) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }

    /**
     * 输入被接受：推进序号、接收水位与业务时间。digest 为输入规范字节的 SHA-256。
     * systemCommand 仅记录系统输入；clientCommand 仅记录领域登记的客户端来源（确认破产/认输）。
     * 演化核对两类来源的类型和摘要后才交给领域建立本步凭据。
     */
    record InputAccepted(long seq, long at, String digest, Command systemCommand, Command clientCommand) implements KernelEvent {
        public InputAccepted(long seq, long at, String digest, Command systemCommand) {
            this(seq, at, digest, systemCommand, null);
        }
        public InputAccepted(long seq, long at, String digest) {
            this(seq, at, digest, null);
        }

        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }

    /** 输入被拒绝：推进序号与接收水位（不低于原水位），不改业务状态、不耗随机数；只私发给发起者。 */
    record InputRejected(long seq, long at, String digest, RejectionCode code, String recipient) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return recipient == null ? Visibility.SERVER_ONLY : Visibility.PRIVATE;
        }
    }

    record TaskScheduled(ScheduledTask task) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }

    record TaskCancelled(long taskId) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }

    /** 定时任务到期出队，业务时间推进到 at（= dueAt）。 */
    record TaskFired(long taskId, long at) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }

    /**
     * 一次随机抽取：协议 ID、抽取点、上界、结果与抽取后状态。正常重建直接采用记录的状态；
     * 审计模式（RandomAudit）按协议重算核对。结果须由紧随的领域事件消费。
     */
    record RandomDrawn(String protocol, DrawPoint point, int bound, int value, RngState after) implements KernelEvent {
        @Override
        public Visibility visibility() {
            return Visibility.SERVER_ONLY;
        }
    }
}
