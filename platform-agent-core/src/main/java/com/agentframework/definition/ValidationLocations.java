package com.agentframework.definition;

/**
 * 校验位置构造器：统一定位语法，避免各处手拼字符串。
 *
 * <pre>
 * workflow:pipeline@1.0.0
 * workflow:pipeline@1.0.0/node:plan
 * workflow:pipeline@1.0.0/node:plan/meta.guardRefs[0]
 * prompt:ops_classify_v2@latest
 * </pre>
 */
public final class ValidationLocations {

    private ValidationLocations() {
    }

    /**
     * @param key 工作流唯一键
     * @return 工作流位置
     */
    public static String workflow(String key) {
        return "workflow:" + key;
    }

    /**
     * @param workflowKey 工作流唯一键
     * @param nodeId      节点 id
     * @return 节点位置
     */
    public static String node(String workflowKey, String nodeId) {
        return workflow(workflowKey) + "/node:" + nodeId;
    }

    /**
     * @param key Prompt 唯一键
     * @return Prompt 位置
     */
    public static String prompt(String key) {
        return "prompt:" + key;
    }

    /**
     * @param base  基础位置
     * @param field 字段名
     * @return 字段位置
     */
    public static String field(String base, String field) {
        return base + "/" + field;
    }

    /**
     * @param base  基础位置
     * @param field 字段名
     * @param index 下标
     * @return 带下标的位置
     */
    public static String indexed(String base, String field, int index) {
        return field(base, field) + "[" + index + "]";
    }
}
