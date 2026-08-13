package com.nageoffer.ai.rag.config.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把任意对象摘要成 OpenObserve 友好的小对象，防止把大 payload（完整检索结果、整篇文档、候选列表全文）
 * 塞进结构化日志导致膨胀。
 *
 * <p>策略：String 截断；Number/Boolean/Character 原样；Collection 记 size + 前 N 条预览；
 * Map 取前 N 个 entry；数组同 Collection；其余对象 Gson 转 JsonElement 后递归摘要（同上策略），
 * 序列化失败时降级 {@code {type, error}}。</p>
 */
public final class Summarizer {

    private static final Gson GSON = new Gson();
    private static final int MAX_STRING = 200;
    private static final int MAX_PREVIEW = 3;
    private static final int MAX_MAP_ENTRIES = 10;

    private Summarizer() {
    }

    public static Object summarize(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof CharSequence c) {
            return truncate(c.toString(), MAX_STRING);
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
        // 其余业务 bean：Gson 转 JsonElement 后递归摘要（字段展开、深层集合记 size+preview、字符串截断），
        // 保证 span attribute / 日志里是小而完整的 JSON，不因 substring 截断而损坏（OpenObserve 解析失败）。
        try {
            return summarizeJsonElement(GSON.toJsonTree(o));
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", o.getClass().getSimpleName());
            m.put("error", "serialize failed: " + e.getMessage());
            return m;
        }
    }

    /** 把方法参数数组摘要成 {@code {paramName:..}}，null/空数组返回 null。
     *  paramNames[i] 缺失/空白时降级 argN（需编译带 -parameters 才有真实参数名）。 */
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
            if (preview.size() >= MAX_PREVIEW) {
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
            if (out.size() >= MAX_MAP_ENTRIES) {
                break;
            }
            out.put(String.valueOf(e.getKey()), summarize(e.getValue()));
        }
        return out;
    }

    private static Object summarizeArray(Object array) {
        int len = Array.getLength(array);
        List<Object> preview = new ArrayList<>();
        for (int i = 0; i < len && preview.size() < MAX_PREVIEW; i++) {
            preview.add(summarize(Array.get(array, i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", len);
        m.put("preview", preview);
        return m;
    }

    /** 递归摘要 Gson JsonElement：对象展开字段（限 N 个）、数组记 size+前 N 条预览、字符串截断。
     *  保证结果是小而完整的 JSON（避免 bean 全展开爆炸，或 substring 截断损坏导致前端解析失败）。 */
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
            return truncate(p.getAsString(), MAX_STRING);
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            List<Object> preview = new ArrayList<>();
            for (int i = 0; i < arr.size() && preview.size() < MAX_PREVIEW; i++) {
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
                if (n++ >= MAX_MAP_ENTRIES) {
                    break;
                }
                m.put(key, summarizeJsonElement(obj.get(key)));
            }
            return m;
        }
        return el.toString();
    }

    /** 序列化为 JSON；超长时降级为完整的小 JSON {_truncated}（而非 substring 截断损坏 JSON）。null 返回 null。 */
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
