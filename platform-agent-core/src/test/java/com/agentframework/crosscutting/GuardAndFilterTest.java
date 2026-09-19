package com.agentframework.crosscutting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.filter.Filters;
import com.agentframework.crosscutting.guard.GuardChain;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.engine.middleware.DefaultMiddlewarePipeline;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 横切层测试：守卫决策与数据过滤。 */
class GuardAndFilterTest {

    @Test
    @DisplayName("守卫链按顺序执行并在首个非放行决策处短路")
    void guardChainShortCircuits() {
        GuardChain chain = new GuardChain(List.of(
                Guards.Content.of("机密"),
                new Guards.AllowAll()));

        GuardDecision allowed = chain.evaluate(GuardContext.of(GuardPhase.BEFORE_AGENT, "正常内容"));
        assertTrue(allowed.allowed());

        GuardDecision denied = chain.evaluate(GuardContext.of(GuardPhase.BEFORE_AGENT, "这是机密文件"));
        assertInstanceOf(GuardDecision.Deny.class, denied);
        assertTrue(denied.reason().contains("机密"));
    }

    @Test
    @DisplayName("守卫可以通过 Transform 改写载荷")
    void guardCanTransformPayload() {
        GuardChain chain = new GuardChain(List.of(new Guards.AllowAll(), new com.agentframework.crosscutting.guard.Guard() {
            @Override
            public String name() {
                return "mask";
            }

            @Override
            public int order() {
                return 50;
            }

            @Override
            public GuardDecision check(GuardContext context) {
                return GuardDecision.transform(context.payloadAsText().replace("密码", "***"), "脱敏");
            }
        }));

        GuardDecision decision = chain.evaluate(GuardContext.of(GuardPhase.BEFORE_OUTPUT, "密码是 123"));
        assertInstanceOf(GuardDecision.Transform.class, decision);
        assertEquals("***是 123", ((GuardDecision.Transform) decision).payload());
    }

    @Test
    @DisplayName("工具权限守卫区分放行、拒绝与待审批")
    void toolPermissionGuard() {
        Guards.ToolPermission guard = new Guards.ToolPermission(
                ToolPolicy.only("calculator", "deploy").withApprovalRequired("deploy"));
        FakeToolCall allowed = new FakeToolCall("calculator");
        FakeToolCall denied = new FakeToolCall("shell");
        FakeToolCall pending = new FakeToolCall("deploy");

        assertTrue(guard.check(GuardContext.of(GuardPhase.BEFORE_TOOL, allowed)).allowed());
        assertInstanceOf(GuardDecision.Deny.class, guard.check(GuardContext.of(GuardPhase.BEFORE_TOOL, denied)));
        assertInstanceOf(GuardDecision.AskApproval.class,
                guard.check(GuardContext.of(GuardPhase.BEFORE_TOOL, pending)));
    }

    @Test
    @DisplayName("RBAC 守卫按角色属性判断")
    void rbacGuard() {
        Guards.Rbac guard = Guards.Rbac.of("admin", "operator");
        GuardContext allow = GuardContext.of(GuardPhase.BEFORE_AGENT, "x")
                .withAttribute("roles", List.of("operator"));
        GuardContext deny = GuardContext.of(GuardPhase.BEFORE_AGENT, "x")
                .withAttribute("roles", List.of("guest"));

        assertTrue(guard.check(allow).allowed());
        assertFalse(guard.check(deny).allowed());
    }

    @Test
    @DisplayName("输出合规守卫限制长度")
    void outputCompliance() {
        Guards.OutputCompliance guard = Guards.OutputCompliance.maxLength(5);
        assertTrue(guard.check(GuardContext.of(GuardPhase.BEFORE_OUTPUT, "12345")).allowed());
        assertFalse(guard.check(GuardContext.of(GuardPhase.BEFORE_OUTPUT, "123456")).allowed());
    }

    @Test
    @DisplayName("PII 过滤器脱敏邮箱、手机号与身份证")
    void piiFilter() {
        Filters.Pii filter = new Filters.Pii();
        String masked = filter.filter("联系 zhang@example.com 或 13812345678，身份证 110101199003071234",
                FilterContext.of(FilterPhase.AFTER_LLM));

        assertFalse(masked.contains("zhang@example.com"));
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("110101199003071234"));
        assertTrue(masked.contains("[已脱敏]"));
    }

    @Test
    @DisplayName("长度、去重与格式化过滤器行为正确")
    void otherFilters() {
        assertEquals("12345…", new Filters.Length(6, null).filter("1234567890", null));
        assertEquals("a\nb", new Filters.Dedup().filter("a\nb\na", null));
        assertEquals("a\n\nb", new Filters.Format().filter("a  \n\n\n\nb\r\n", null));
    }

    @Test
    @DisplayName("过滤链按 order 排序执行")
    void filterChainOrdering() {
        List<String> applied = new java.util.ArrayList<>();
        Filter<String, String> first = new OrderedFilter("first", 1, applied);
        Filter<String, String> second = new OrderedFilter("second", 2, applied);
        com.agentframework.crosscutting.filter.FilterChain<String> chain =
                new com.agentframework.crosscutting.filter.FilterChain<String>().add(second).add(first);

        chain.apply("x", FilterContext.of(FilterPhase.PROMPT));
        assertEquals(List.of("first", "second"), applied);
    }

    @Test
    @DisplayName("中间件管道按类型派发过滤器，避免类型不匹配")
    void middlewarePipelineDispatchesByType() {
        DefaultMiddlewarePipeline pipeline = new DefaultMiddlewarePipeline();
        pipeline.register(new Filters.Dedup());
        pipeline.register(new MapUpperFilter());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "abc");
        Map<String, Object> result = pipeline.filter(FilterPhase.AFTER_NODE_OUTPUT, input,
                FilterContext.of(FilterPhase.AFTER_NODE_OUTPUT));

        assertEquals("ABC", result.get("name"));
    }

    @Test
    @DisplayName("守卫链拒绝时 requireGuard 抛出 GuardDeniedException")
    void requireGuardThrows() {
        DefaultMiddlewarePipeline pipeline = new DefaultMiddlewarePipeline();
        pipeline.register(Guards.Content.of("禁止"));

        assertThrows(com.agentframework.crosscutting.guard.GuardDeniedException.class,
                () -> pipeline.requireGuard(GuardPhase.BEFORE_AGENT,
                        GuardContext.of(GuardPhase.BEFORE_AGENT, "包含禁止词")));
    }

    /** 供守卫测试使用的假工具调用载荷。 */
    private record FakeToolCall(String toolId) implements com.agentframework.crosscutting.guard.ToolAware {
    }

    /** 记录执行顺序的测试过滤器。 */
    private record OrderedFilter(String name, int order, List<String> applied) implements Filter<String, String> {
        @Override
        public String filter(String input, FilterContext context) {
            applied.add(name);
            return input + "+" + name;
        }
    }

    /** 把 Map 值转大写的测试过滤器。 */
    private static final class MapUpperFilter implements Filter<Map<String, Object>, Map<String, Object>> {

        @Override
        public String name() {
            return "map-upper";
        }

        @Override
        public Map<String, Object> filter(Map<String, Object> input, FilterContext context) {
            Map<String, Object> result = new LinkedHashMap<>();
            input.forEach((key, value) -> result.put(key, String.valueOf(value).toUpperCase()));
            return result;
        }
    }
}
