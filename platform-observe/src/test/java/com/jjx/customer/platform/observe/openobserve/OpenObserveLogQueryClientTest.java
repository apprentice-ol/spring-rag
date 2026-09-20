package com.jjx.customer.platform.observe.openobserve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 日志查询的服务排除谓词单测（纯函数，无 IO）。
 *
 * <p><b>为什么值得钉</b>：这道谓词是"日志反查不读平台自己的日志"的唯一保障。少了它，
 * 平台把发给 LLM 的 prompt 打进日志、prompt 模板里又写着「请求报文 / 业务系统响应」，
 * 下一轮反查就会把自家日志当业务报文抓回槽位——一条会自我循环的环（本地实测跑通过）。
 * 它拼错不会报错，只会静默把垃圾当成业务证据，是最难事后归因的那类缺陷。</p>
 */
class OpenObserveLogQueryClientTest {

    @Test
    void 未配置排除项时不加谓词() {
        assertEquals("", OpenObserveLogQueryClient.serviceExclusion("service_name", null));
        assertEquals("", OpenObserveLogQueryClient.serviceExclusion("service_name", ""));
        assertEquals("", OpenObserveLogQueryClient.serviceExclusion("service_name", "   "));
        // 全是分隔符/空白也一样：不能拼出个空的 != '' 把日志查空
        assertEquals("", OpenObserveLogQueryClient.serviceExclusion("service_name", " , ,, "));
    }

    @Test
    void 单个服务名拼成一句不等谓词() {
        assertEquals("service_name != 'customer-platform'",
                OpenObserveLogQueryClient.serviceExclusion("service_name", "customer-platform"));
    }

    @Test
    void 多个服务名用AND连接并去掉空白项() {
        assertEquals("service_name != 'customer-platform' AND service_name != 'rag-console'",
                OpenObserveLogQueryClient.serviceExclusion(
                        "service_name", " customer-platform , rag-console , "));
    }

    /** 服务列名没配（或配成空白）时回落到 OTel 约定的 service_name，而不是拼出 `null != '…'`。 */
    @Test
    void 服务列名缺省回落() {
        assertEquals("service_name != 'x'", OpenObserveLogQueryClient.serviceExclusion(null, "x"));
        assertEquals("service_name != 'x'", OpenObserveLogQueryClient.serviceExclusion("  ", "x"));
    }

    /** 服务名进的是单引号字符串字面量，必须转义——否则一个引号就能把整条 SQL 拆坏。 */
    @Test
    void 服务名里的单引号被转义() {
        assertEquals("service_name != 'o''brien'",
                OpenObserveLogQueryClient.serviceExclusion("service_name", "o'brien"));
    }
}
