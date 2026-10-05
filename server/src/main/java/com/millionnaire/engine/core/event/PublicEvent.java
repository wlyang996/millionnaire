package com.millionnaire.engine.core.event;

/** 显式声明"公开"的标记：具体事件 record 逐个实现它才会进入公共投影。 */
public interface PublicEvent extends Event {
    @Override
    default Visibility visibility() {
        return Visibility.PUBLIC;
    }
}
