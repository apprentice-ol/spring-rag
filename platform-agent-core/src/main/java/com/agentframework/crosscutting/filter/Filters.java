package com.agentframework.crosscutting.filter;

import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.MessageRole;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内置过滤器集合。
 *
 * <p>过滤器只改数据、不做决策：涉及“能不能做”的判断请使用 {@code Guard}。</p>
 */
public final class Filters {

    private Filters() {
    }

    /**
     * 空过滤器：不做任何变换，用作占位与测试基线。
     *
     * @param <T> 数据类型
     */
    public static final class Noop<T> implements Filter<T, T> {

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public T filter(T input, FilterContext context) {
            return input;
        }
    }

    /** PII 脱敏：邮箱、手机号、身份证、银行卡统一替换为掩码。 */
    public static final class Pii implements Filter<String, String> {

        private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
        private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
        private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

        private final String mask;

        /** @param mask 替换文本 */
        public Pii(String mask) {
            this.mask = mask == null ? "[已脱敏]" : mask;
        }

        /** 使用默认掩码构造。 */
        public Pii() {
            this(null);
        }

        @Override
        public String name() {
            return "pii";
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public String filter(String input, FilterContext context) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            String masked = EMAIL.matcher(input).replaceAll(mask);
            masked = PHONE.matcher(masked).replaceAll(mask);
            masked = ID_CARD.matcher(masked).replaceAll(mask);
            return BANK_CARD.matcher(masked).replaceAll(mask);
        }
    }

    /** 长度控制：超过上限时截断并追加省略标记。 */
    public static final class Length implements Filter<String, String> {

        private final int maxLength;
        private final String ellipsis;

        /**
         * @param maxLength 最大字符数，≤0 表示不限制
         * @param ellipsis  截断后追加的标记
         */
        public Length(int maxLength, String ellipsis) {
            this.maxLength = maxLength;
            this.ellipsis = ellipsis == null ? "…" : ellipsis;
        }

        /**
         * @param maxLength 最大字符数
         * @return 使用默认省略号的过滤器
         */
        public static Length max(int maxLength) {
            return new Length(maxLength, null);
        }

        @Override
        public String name() {
            return "length";
        }

        @Override
        public int order() {
            return 40;
        }

        @Override
        public String filter(String input, FilterContext context) {
            if (input == null || maxLength <= 0 || input.length() <= maxLength) {
                return input;
            }
            return input.substring(0, Math.max(0, maxLength - ellipsis.length())) + ellipsis;
        }
    }

    /** 上下文窗口控制：保留全部系统消息，其余消息仅保留最近 N 条。 */
    public static final class ContextWindow implements Filter<List<Message>, List<Message>> {

        private final int maxMessages;

        /** @param maxMessages 非系统消息的保留条数，≤0 表示不裁剪 */
        public ContextWindow(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        @Override
        public String name() {
            return "context-window";
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public List<Message> filter(List<Message> input, FilterContext context) {
            if (input == null || maxMessages <= 0 || input.size() <= maxMessages) {
                return input;
            }
            List<Message> system = input.stream().filter(message -> message.role() == MessageRole.SYSTEM).toList();
            List<Message> rest = input.stream().filter(message -> message.role() != MessageRole.SYSTEM).toList();
            List<Message> kept = new ArrayList<>(system);
            kept.addAll(rest.subList(Math.max(0, rest.size() - maxMessages), rest.size()));
            return kept;
        }
    }

    /** 去重：去掉完全重复的行，保持首次出现顺序。 */
    public static final class Dedup implements Filter<String, String> {

        @Override
        public String name() {
            return "dedup";
        }

        @Override
        public int order() {
            return 30;
        }

        @Override
        public String filter(String input, FilterContext context) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>(input.lines().toList());
            return String.join("\n", unique);
        }
    }

    /** 格式化：统一换行、去掉行尾空白、把连续空行压缩为一行。 */
    public static final class Format implements Filter<String, String> {

        @Override
        public String name() {
            return "format";
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public String filter(String input, FilterContext context) {
            if (input == null) {
                return null;
            }
            String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
            List<String> lines = normalized.lines().map(String::stripTrailing).toList();
            StringBuilder builder = new StringBuilder();
            boolean previousBlank = true;
            for (String line : lines) {
                boolean blank = line.isBlank();
                if (blank && previousBlank) {
                    continue;
                }
                builder.append(line).append('\n');
                previousBlank = blank;
            }
            return builder.toString().stripTrailing();
        }
    }
}
