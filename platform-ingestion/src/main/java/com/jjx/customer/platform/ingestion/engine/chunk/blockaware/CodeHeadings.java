package com.jjx.customer.platform.ingestion.engine.chunk.blockaware;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通用代码子标题的公共判定（CodeChunker 选章节 与 HeadingHandler 压栈路径共用）。
 * <p>此前两处各自维护一份黑名单（一边 Set、一边 equals 串联），已经发生风格漂移——
 * 改一处漏一处会让「代码块章节归属」与「outline 顶掉」行为不一致。</p>
 */
final class CodeHeadings {

    /** 标题前的 markdown 井号前缀 */
    private static final Pattern LEADING_HASHES = Pattern.compile("^#+\\s*");

    /** 无主题词的通用代码子标题（MinerU 常把代码归到这些标题下） */
    static final Set<String> GENERIC_CODE_HEADINGS = Set.of(
            "实现代码", "代码", "代码示例", "示例", "Code", "Implementation", "代码实现");

    private CodeHeadings() {
    }

    /** 去掉井号前缀的标题文本 */
    static String stripHeading(String text) {
        return LEADING_HASHES.matcher(text).replaceAll("").trim();
    }

    /** 是否为无主题词的通用代码标题 */
    static boolean isGeneric(String text) {
        return GENERIC_CODE_HEADINGS.contains(stripHeading(text));
    }
}
