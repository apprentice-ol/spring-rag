package com.nageoffer.ai.rag.common.util;

import java.util.regex.Pattern;

/**
 * 用户查询归一化工具。
 * <p>
 * 在意图分类与检索前对用户输入做清洗，去除与语义无关的噪声，提升分类与召回质量。
 * <b>不改写语义内容</b>，仅做：全角→半角、压缩空白、去无意义前缀（请问/你好等）、去首尾标点。
 * </p>
 *
 * <p>示例：
 * <ul>
 *   <li>"请问一下，Ｓｐｒｉｎｇ ＡＩ 怎么用？" → "Spring AI 的使用方式"</li>
 *   <li>"  你好，麻烦问下 ｐｇｖｅｃｔｏｒ 是啥！！" → "pgvector 的概念介绍"</li>
 *   <li>"RAG 的流程" → "RAG 的流程"（无变化）</li>
 * </ul>
 * </p>
 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    /**
     * 无意义前缀；按长度降序排列以优先匹配长串（"请问一下"先于"请问"）。
     * 前缀后允许跟随标点或空白，一并去除。可叠加（"你好，请问一下"）。
     */
    private static final Pattern NOISE_PREFIX = Pattern.compile(
            "^(?:请问一下|麻烦问一下|麻烦问下|帮我问一下|我想问一下|我想问|我想知道|"
                    + "咨询一下|请教一下|请问|问一下|帮我问|您好|你好|哈喽|嗨)"
                    + "[\\s,.!?;:，。！？；：]*");

    /** 首部标点（中英文），归一化后去除 */
    private static final Pattern LEAD_PUNCT = Pattern.compile(
            "^[\\p{Punct}\\s，。！？、；：“”‘’（）《》【】「」『』·…]+");
    /** 尾部标点（中英文），归一化后去除 */
    private static final Pattern TAIL_PUNCT = Pattern.compile(
            "[\\p{Punct}\\s，。！？、；：“”‘’（）《》【】「」『』·…]+$");

    /** 连续空白压缩（热路径，避免 replaceAll 每次隐式编译） */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 连续"的"清理 */
    private static final Pattern CONSECUTIVE_DE = Pattern.compile("的{2,}");

    /**
     * 疑问句→陈述句改写规则（模式 + 替换串成对）。
     */
    private record RewriteRule(Pattern pattern, String replacement) {
    }

    /** 改写规则表；Pattern 全部预编译（本方法在每次查询的归一化路径上，do-while 还可能多轮） */
    private static final RewriteRule[] DECLARATIVE_RULES = {
            new RewriteRule(Pattern.compile("(?:怎么|如何|咋)(?:使用|用)"), "的使用方式"),
            new RewriteRule(Pattern.compile("(?:怎么|如何)(?:配置|设置|开启|启用)"), "的配置方法"),
            new RewriteRule(Pattern.compile("(?:怎么|如何)(?:实现)"), "的实现方式"),
            new RewriteRule(Pattern.compile("(?:怎么|如何)(?:部署|安装|调用)"), "的使用方法"),
            new RewriteRule(Pattern.compile("为什么(?:要)?用\\s*(.+)"), "使用 $1 的原因"),
            new RewriteRule(Pattern.compile("为什么\\s*(.+)"), "$1的原因"),
            new RewriteRule(Pattern.compile("是(?:什么|啥|干嘛的|干啥的)"), "的概念介绍"),
            new RewriteRule(Pattern.compile("有(?:哪些|什么类型|啥类型)"), "的类型"),
            new RewriteRule(Pattern.compile("有(?:什么用|啥用|什么作用|啥作用)"), "的作用"),
    };

    /**
     * 归一化用户查询。
     *
     * @param raw 原始输入
     * @return 归一化后的查询；输入为空返回空串
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 1. 全角→半角（英数字与常见标点）
        String s = toHalfWidth(raw);
        // 2. 压缩连续空白
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        // 3. 去无意义前缀（循环，应对"你好，请问一下"这类叠加）
        String prev;
        do {
            prev = s;
            s = NOISE_PREFIX.matcher(s).replaceFirst("");
        } while (!s.equals(prev));
        // 4. 去首尾标点
        s = LEAD_PUNCT.matcher(s).replaceFirst("");
        s = TAIL_PUNCT.matcher(s).replaceFirst("");
        // 5. 疑问句→陈述句改写（规则式名词化，贴近文档表述）
        s = rewriteToDeclarative(s);
        return s.trim();
    }

    /**
     * 疑问句→陈述句改写（规则式）。
     * <p>把常见疑问尾缀名词化（"怎么用"→"的使用方式"），使 query 更贴近文档的陈述性表述，
     * 提升关键词与向量召回。<b>仅覆盖常见模式</b>，复杂句式可能不改；不改语义。</p>
     *
     * <p>局限：纯规则无法覆盖所有中文问法（如"怎么用 X"这类疑问词在句首的，改写后位置可能不理想）。
     * 要求高时建议改用 LLM 改写。</p>
     */
    private static String rewriteToDeclarative(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        String prev;
        do {
            prev = s;
            for (RewriteRule rule : DECLARATIVE_RULES) {
                s = rule.pattern().matcher(s).replaceAll(rule.replacement());
            }
        } while (!s.equals(prev));
        // 清理可能出现的连续"的"
        s = CONSECUTIVE_DE.matcher(s).replaceAll("的");
        return s;
    }

    /**
     * 全角字符（！~～）转半角；全角空格（　）转普通空格。
     * 中文专用标点（。、「」等）不在该范围，保持原样。
     */
    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
