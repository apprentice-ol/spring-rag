package com.nageoffer.ai.obs.observation.support;

import com.google.gson.*;
import com.nageoffer.ai.obs.observation.processor.SummarizeProcessor;

import java.lang.reflect.Array;
import java.util.*;

/**
 * 把任意对象摘要成观测友好（span attribute / 日志）的小对象，防止大 payload 膨胀。
 *
 * <p><b>所属维度</b>：转（processor 层工具，被 {@link SummarizeProcessor} 与 llm 集成调用）。</p>
 *
 * <p><b>职责</b>：String 截断；Number/Boolean/Character 原样；Collection/数组记 size + 前 N 条预览；
 * Map 取前 N entry；其余对象 Gson 转 JsonElement 后递归摘要，序列化失败降级 {@code {type, error}}。</p>
 *
 * <p><b>可配置</b>：三个摘要粒度默认 200/3/10，可经 {@code obs.limits.summarize-*} 覆盖
 * （ObsAutoConfiguration 启动期调 {@link #configure}，启动后不再变更）。</p>
 */
public final class Summarizer {

    private static final Gson GSON = new Gson();
    private static volatile int maxString = 200;
    private static volatile int maxPreview = 3;
    private static volatile int maxMapEntries = 10;

    private Summarizer() {
    }

    /** 覆盖摘要粒度（启动期配置一次；非正值忽略）。 */
    public static void configure(int newMaxString, int newMaxPreview, int newMaxMapEntries) {
        if (newMaxString > 0) {
            maxString = newMaxString;
        }
        if (newMaxPreview > 0) {
            maxPreview = newMaxPreview;
        }
        if (newMaxMapEntries > 0) {
            maxMapEntries = newMaxMapEntries;
        }
    }

    public static Object summarize(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof CharSequence c) {
            return truncate(c.toString(), maxString);
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character) {
            return o;
        }
        if (o instanceof Collection<?> coll) {
            return summarizeCollection(coll);
        }
        if (o instanceof Map<?, ?> map) {
            return summarizeMap(map);
        }
        if (o.getClass().isArray()) {
            return summarizeArray(o);
        }
        try {
            return summarizeJsonElement(GSON.toJsonTree(o));
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", o.getClass().getSimpleName());
            m.put("error", "serialize failed: " + e.getMessage());
            return m;
        }
    }

    /** 把方法参数数组摘要成 {@code {paramName:..}}，null/空数组返回 null。 */
    public static Object summarizeArgs(Object[] args, String[] paramNames) {
        if (args == null || args.length == 0) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = (paramNames != null && i < paramNames.length
                    && paramNames[i] != null && !paramNames[i].isBlank())
                    ? paramNames[i] : "arg" + i;
            out.put(name, summarize(args[i]));
        }
        return out;
    }

    private static Object summarizeCollection(Collection<?> coll) {
        List<Object> preview = new ArrayList<>();
        for (Object e : coll) {
            if (preview.size() >= maxPreview) {
                break;
            }
            preview.add(summarize(e));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", coll.size());
        m.put("preview", preview);
        return m;
    }

    private static Object summarizeMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (out.size() >= maxMapEntries) {
                break;
            }
            out.put(String.valueOf(e.getKey()), summarize(e.getValue()));
        }
        return out;
    }

    private static Object summarizeArray(Object array) {
        int len = Array.getLength(array);
        List<Object> preview = new ArrayList<>();
        for (int i = 0; i < len && preview.size() < maxPreview; i++) {
            preview.add(summarize(Array.get(array, i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", len);
        m.put("preview", preview);
        return m;
    }

    private static Object summarizeJsonElement(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsNumber();
            }
            return truncate(p.getAsString(), maxString);
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            List<Object> preview = new ArrayList<>();
            for (int i = 0; i < arr.size() && preview.size() < maxPreview; i++) {
                preview.add(summarizeJsonElement(arr.get(i)));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", arr.size());
            m.put("preview", preview);
            return m;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            Map<String, Object> m = new LinkedHashMap<>();
            int n = 0;
            for (String key : obj.keySet()) {
                if (n++ >= maxMapEntries) {
                    break;
                }
                m.put(key, summarizeJsonElement(obj.get(key)));
            }
            return m;
        }
        return el.toString();
    }

    /** 序列化为 JSON；超长降级为 {@code {_truncated}}（而非 substring 截断损坏 JSON）。null 返回 null。 */
    public static String toJsonTruncated(Object o, int max) {
        if (o == null) {
            return null;
        }
        String json = GSON.toJson(o);
        if (json.length() <= max) {
            return json;
        }
        return "{\"_truncated\":true,\"length\":" + json.length() + "}";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
