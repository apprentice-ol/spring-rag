package com.jjx.customer.platform.business.task;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主张抽取：夹具取自**真实生产输出**（数据 2026-09-19 真机诊断产生的结论原文），
 * 不是手造的理想文本——手造夹具会掩盖模型实际书写格式的偏差。
 */
class FindingExtractorTest {

    /** 真机输出（sa_message id=1284，含服务端追加的上下文段）。 */
    private static final String REAL_CONCLUSION = """
            **排查结论**
            order-service 在 prod 环境调用 POST /api/invoice/reverse 时，请求体 invoiceCode 传了空字符串（""），被服务端参数校验直接拦截，返回错误码 INVOICE_CODE_REQUIRED（invoiceCode 不能为空），success=false，未进入后续红冲业务逻辑。属于入参缺失，不是服务端异常。

            **证据链**
            - 接口：/api/invoice/reverse（traceId 4fbdabbe672b82934aa42c6664d367d2 精查命中接口路径）
            - 报错：order-service ERROR 下单失败：POST /api/invoice/reverse InvalidParamException: invoiceCode 不能为空（来源：日志，traceId 4fbdabbe672b82934aa42c6664d367d2）
            - 请求报文：{"invoiceCode":"","invoiceNumber":"00412345","reason":"INVOICE_ERROR","operator":"zhangsan"}（来源：日志，invoiceCode 为空串）
            - 业务响应：{"code":"INVOICE_CODE_REQUIRED","message":"invoiceCode 不能为空","success":false}（来源：日志，业务系统原样返回）
            - 时间：2026-09-19T11:47:42.158Z（UTC，北京时间 19:47:42）（来源：日志）
            - 校验结果：validate_request(iface=invoice_reverse) 对原报文返回「✅ 校验通过：报文符合 invoice_reverse 规范」（来源：工具校验结果）

            **修正动作**
            校验通过，但需注意：校验器只校验字段结构，不校验空串语义，invoiceCode 为空串仍会被 order-service 业务校验拦截。修正后报文如下：

            ```json
            {
              "invoiceCode": "00412345",
              "invoiceNumber": "00412345",
              "reason": "INVOICE_ERROR",
              "operator": "zhangsan"
            }
            ```

            | 字段 | 原值 | 改为 | 说明 |
            |---|---|---|---|
            | invoiceCode | ""（空串） | 待填真实发票代码（示例占位 00412345） | 本次失败的直接原因 |

            说明：invoiceCode 的真实取值需从上游调用方持有的蓝字发票信息中获取。

            **风险提醒**
            - 环境为 prod，且 /api/invoice/reverse 为发票红冲类不可逆操作：重试前务必确认该蓝字发票尚未被红冲成功，避免重复红冲导致重复冲减。
            - 本次失败发生在参数校验阶段（success=false，未进入业务逻辑），理论上未产生红冲副作用；但重试前仍建议按 invoiceNumber 查询该发票当前红冲状态。
            - 建议上游调用方在发起前对 invoiceCode 做非空校验，避免空串再次打到 order-service。

            ——
            本次诊断的上下文自动补全：
            已自动补全：
            - time = 2026-09-19T22:58~2026-09-19T23:28（缺省策略：最近30分钟）
            - interface = /api/invoice/reverse（traceId 精查日志命中接口路径）
            """;

    @Test
    void 从真实四段式结论抽出主张_段数与类型正确() {
        List<AgentFinding> findings = FindingExtractor.extract("t1", "c1", 1, REAL_CONCLUSION);

        assertEquals(5, findings.size(), "1 条根因 + 1 条修正动作 + 3 条风险");
        assertEquals(1, count(findings, AgentFinding.Kind.ROOT_CAUSE));
        assertEquals(1, count(findings, AgentFinding.Kind.FIX));
        assertEquals(3, count(findings, AgentFinding.Kind.RISK));
    }

    @Test
    void 根因取排查结论段全文() {
        AgentFinding root = firstOf(FindingExtractor.extract("t1", "c1", 1, REAL_CONCLUSION),
                AgentFinding.Kind.ROOT_CAUSE);

        assertTrue(root.claim().startsWith("order-service 在 prod 环境调用"), "实际=" + root.claim());
        assertTrue(root.claim().contains("INVOICE_CODE_REQUIRED"));
        assertFalse(root.claim().contains("**"), "段落标记不应混进正文");
    }

    @Test
    void 证据链逐条解析为标签取值与来源() {
        AgentFinding root = firstOf(FindingExtractor.extract("t1", "c1", 1, REAL_CONCLUSION),
                AgentFinding.Kind.ROOT_CAUSE);
        List<AgentFinding.Evidence> evidence = root.evidence();

        assertEquals(6, evidence.size(), "真实输出有 6 条证据");
        assertEquals(List.of("接口", "报错", "请求报文", "业务响应", "时间", "校验结果"),
                evidence.stream().map(AgentFinding.Evidence::label).toList());
        assertEquals("/api/invoice/reverse", evidence.get(0).value());
        assertTrue(evidence.get(0).source().contains("traceId 4fbdabbe"), "来源应保留可回查的指针");
        assertTrue(evidence.get(4).value().contains("（UTC，北京时间 19:47:42）"),
                "取值自带的全角括号不应被当成来源截断：" + evidence.get(4).value());
        assertEquals("日志", evidence.get(4).source(), "来源要取最后一对括号");
    }

    @Test
    void 服务端追加的上下文段不会被当成主张抽出来() {
        List<AgentFinding> findings = FindingExtractor.extract("t1", "c1", 1, REAL_CONCLUSION);

        assertTrue(findings.stream().noneMatch(f -> f.claim().contains("本次诊断的上下文自动补全")),
                "上下文自动补全段是执行元数据，不属于诊断结论");
        assertTrue(findings.stream().noneMatch(f -> f.claim().contains("缺省策略")),
                "自动补全条目不得渗入主张");
    }

    @Test
    void 风险逐条成主张以便局部否定() {
        AgentFinding risk = FindingExtractor.extract("t1", "c1", 1, REAL_CONCLUSION).stream()
                .filter(f -> f.kind() == AgentFinding.Kind.RISK).findFirst().orElseThrow();

        assertTrue(risk.claim().startsWith("环境为 prod"), "实际=" + risk.claim());
        assertFalse(risk.claim().startsWith("-"), "列表符号应剥掉");
    }

    @Test
    void 抽取出的主张一律为生效态并带上attempt序号() {
        List<AgentFinding> findings = FindingExtractor.extract("t9", "c9", 3, REAL_CONCLUSION);

        assertTrue(findings.stream().allMatch(f -> f.status() == AgentFinding.Status.ACTIVE
                && f.attemptNo() == 3 && "t9".equals(f.taskId())));
        assertEquals(findings.size(), findings.stream().map(AgentFinding::findingId).distinct().count(),
                "主张 id 不得重复");
    }

    // ---- 降级：结论不守格式时必须少抽，而不是抛异常 ----

    @Test
    void 非四段式文本不抽任何主张() {
        // 旧版散文式结论（改格式之前的真实输出形态）
        List<AgentFinding> findings = FindingExtractor.extract("t1", "c1", 1,
                "根因：调用 /api/invoice/reverse 时 invoiceCode 传了空字符串。给出的动作：补成真实发票代码后重试。");

        assertTrue(findings.isEmpty(), "没有段落标记就不该硬抽——宁缺勿滥");
    }

    @Test
    void 缺段时只抽存在的部分() {
        List<AgentFinding> findings = FindingExtractor.extract("t1", "c1", 1,
                "**排查结论**\n字段缺失导致校验失败。\n\n**风险提醒**\n- 生产环境重试需谨慎。");

        assertEquals(2, findings.size(), "只有根因与风险两段");
        assertEquals(1, count(findings, AgentFinding.Kind.ROOT_CAUSE));
        assertEquals(1, count(findings, AgentFinding.Kind.RISK));
    }

    @Test
    void 空文本与null都不抛异常() {
        assertTrue(FindingExtractor.extract("t1", "c1", 1, null).isEmpty());
        assertTrue(FindingExtractor.extract("t1", "c1", 1, "   ").isEmpty());
    }

    // ---- 主张变更：用户否定某条旧主张时按编号精确落位 ----

    @Test
    void 解析主张变更段里被否定的编号() {
        String conclusion = """
                **排查结论**
                重试前仍需确认红冲状态。

                **主张变更**
                - #3 否定：用户指出 invoiceNumber 唯一，不存在重复红冲
                - #5 推翻：该字段并非必填
                """;

        assertEquals(java.util.Set.of(3, 5), FindingExtractor.deniedIndices(conclusion));
    }

    @Test
    void 主张变更段里未被否定的条目不算() {
        String conclusion = """
                **主张变更**
                - #1 保持：与用户判断一致
                - #2 保留：证据充分
                """;

        assertTrue(FindingExtractor.deniedIndices(conclusion).isEmpty(),
                "只列不复述——没有否定词就不该标 RETRACTED");
    }

    @Test
    void 没有主张变更段时返回空集() {
        assertTrue(FindingExtractor.deniedIndices(REAL_CONCLUSION).isEmpty());
        assertTrue(FindingExtractor.deniedIndices("随便一段没有段落标记的话").isEmpty());
        assertTrue(FindingExtractor.deniedIndices(null).isEmpty());
    }

    @Test
    void 主张变更段之后的追加内容不会干扰编号解析() {
        // 该段在格式里排在最后，其后可能跟着服务端追加的上下文段（含 - key = value 行）
        String conclusion = """
                **风险提醒**
                - 重试需谨慎。

                **主张变更**
                - #2 否定：用户提供了反例

                ——
                本次诊断的上下文自动补全：
                已自动补全：
                - time = 2026-09-19T22:58~2026-09-19T23:28（缺省策略：最近30分钟）
                """;

        assertEquals(java.util.Set.of(2), FindingExtractor.deniedIndices(conclusion),
                "服务端追加段里的 - key = value 行不能被当成主张变更条目");
    }

    @Test
    void 抽取主张时不会把主张变更段当成正文() {
        String conclusion = """
                **排查结论**
                根因是入参缺失。

                **主张变更**
                - #1 否定：用户指出判断有误
                """;
        List<AgentFinding> findings = FindingExtractor.extract("t1", "c1", 2, conclusion);

        assertEquals(1, findings.size(), "只该抽出根因一条");
        assertFalse(findings.getFirst().claim().contains("否定"), "变更段不该混进主张正文");
    }

    private static long count(List<AgentFinding> findings, AgentFinding.Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).count();
    }

    private static AgentFinding firstOf(List<AgentFinding> findings, AgentFinding.Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).findFirst().orElseThrow();
    }
}
