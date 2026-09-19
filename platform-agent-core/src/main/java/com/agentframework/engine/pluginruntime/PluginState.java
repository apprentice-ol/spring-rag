package com.agentframework.engine.pluginruntime;

/** 插件生命周期状态。 */
public enum PluginState {
    DISCOVERED,
    LOADED,
    INITIALIZED,
    FAILED,
    CLOSED
}
