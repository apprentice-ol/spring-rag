package com.agentframework.extension.manifest;

/** 插件隔离级别：从进程内到强隔离，安全性与成本依次提升。 */
public enum Isolation {
    /** 进程内直接执行，性能最好，仅适合受信任插件。 */
    NONE,
    /** 独立进程执行，可限制 CPU / 内存。 */
    PROCESS,
    /** 容器执行，隔离最彻底。 */
    CONTAINER,
    /** WASM 沙箱执行，启动快、可精细限制能力。 */
    WASM
}
