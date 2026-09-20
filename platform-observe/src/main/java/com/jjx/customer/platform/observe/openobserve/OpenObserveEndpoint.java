package com.jjx.customer.platform.observe.openobserve;

/**
 * OpenObserve 查询端点配置契约（数据面：日志检索）。
 *
 * <p>消费方（如 business 的 ops 诊断工具）各自持有配置源（{@code rag.ops.*} / {@code telemetry.*}），
 * 把自己的配置 record 实现本接口即可复用 {@link OpenObserveLogQueryClient}——
 * 客户端不关心配置从哪来，只按契约读端点四要素与查询参数。</p>
 */
public interface OpenObserveEndpoint {

    /** @return 服务地址（如 http://host:5080） */
    String url();

    /** @return 账号 */
    String username();

    /** @return 密码 */
    String password();

    /** @return 默认日志 stream 名 */
    String stream();

    /** @return traceId 精查回溯窗口（天） */
    int lookbackDays();

    /** @return 查询开关（false 时客户端不可用） */
    boolean queryEnabled();

    /** @return 请求超时（秒） */
    int timeoutSeconds();

    /** @return 服务列名（缺省 service_name，OTel 语义约定） */
    default String serviceField() {
        return "service_name";
    }

    /**
     * 需要排除的服务名（逗号分隔）；默认空 = 不排除。
     *
     * <p><b>平台自己的日志不是业务证据。</b>日志反查的用途是"读业务系统的日志"，
     * 把自身日志一并读回会形成自我循环：平台把发给 LLM 的 prompt 打进日志 →
     * 而 prompt 模板里本身就写着「请求报文 / 业务系统响应」这类字样 →
     * 下一轮反查把这些日志当成业务报文抓回槽位（本地实测跑通过这条环：
     * 意图分类器的 JSON 被当成"请求报文"和"业务响应"补进槽位，再进 prompt、再被读回）。</p>
     */
    default String excludeServices() {
        return "";
    }
}
