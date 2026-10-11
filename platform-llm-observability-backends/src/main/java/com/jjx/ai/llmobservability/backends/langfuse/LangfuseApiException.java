package com.jjx.ai.llmobservability.backends.langfuse;

/**
 * Langfuse 公共 API 调用异常。
 *
 * <p>HTTP 非 2xx、通信失败或响应解析失败时抛出；调用方按业务需要决定是中断还是降级。</p>
 */
public class LangfuseApiException extends RuntimeException {

    /** HTTP 状态码；通信层异常时为 0。 */
    private final int statusCode;

    /** 后端返回的响应体（可为 null）。 */
    private final String responseBody;

    /**
     * 构造 HTTP 异常。
     *
     * @param message      异常描述
     * @param statusCode   HTTP 状态码
     * @param responseBody 后端响应体（可为 null）
     */
    public LangfuseApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * 构造通信/解析异常。
     *
     * @param message 异常描述
     * @param cause   底层异常
     */
    public LangfuseApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码；通信层异常时为 0
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 获取后端响应体。
     *
     * @return 后端响应体；无响应时为 null
     */
    public String getResponseBody() {
        return responseBody;
    }
}
