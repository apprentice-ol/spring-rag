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
}
