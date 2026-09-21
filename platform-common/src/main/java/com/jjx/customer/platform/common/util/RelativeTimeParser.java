package com.jjx.customer.platform.common.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 相对时间表达的确定性换算（auto-resolve 规则快通道）：把口语时间换算成
 * {@code start~end} 的 ISO-8601 窗口，供 {@code query_logs} 直接使用。
 *
 * <p>两类表达，整段窗口级与点级：</p>
 * <ul>
 *   <li><b>窗口级</b>：「最近N小时 / N分钟前 / 今天 / 昨天 / 本周」→ 对应整段；</li>
 *   <li><b>点级</b>：「今天下午3点 / 昨天上午10点半 / 9月15日下午3点 / 晚上八点」
 *       → 具体时刻 <b>±30 分钟</b>（「今天下午3点」= 14:30~15:30）——用户凭印象说的
 *       时间总有偏差，半小说不清就扩到半小时。</li>
 * </ul>
 *
 * <p>无法识别的文本返回 null，交给 LLM 推断层兜底。测试经 {@link Clock} 注入固定时钟。</p>
 */
public final class RelativeTimeParser {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private static final Pattern LAST_N = Pattern.compile("最近(\\d+)(分钟|小时|天|日)");
    private static final Pattern N_AGO = Pattern.compile("(\\d+)(分钟|小时)前");
    private static final Pattern DAY_ONLY = Pattern.compile("(今天|昨天|前天)");
    private static final Pattern WEEK = Pattern.compile("(本|这)周");

    /** 绝对日期：「9月15日 / 9月15号」（年份缺省取当年）。 */
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    /** 点级时刻：时段词（可选）+ 小时（阿拉伯或中文数字）+ [点时] + 分钟（可选，「半」=30）。 */
    private static final Pattern CLOCK = Pattern.compile(
            "(上午|早上|清晨|凌晨|中午|午后|下午|傍晚|晚上|今晚|夜里|夜间)?"
                    + "\\s*(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*[点时：:]\\s*(\\d{1,2}|半)?\\s*分?");

    /** 点级表达的浮动半径（分钟）：口语时刻的常见误差带。 */
    static final int POINT_RADIUS_MINUTES = 30;

    /** 时段词 → [起小时, 止小时)（止 24 = 当天午夜，窗口跨到次日 0 点）。 */
    private static final java.util.LinkedHashMap<String, int[]> PERIODS = new java.util.LinkedHashMap<>();

    static {
        PERIODS.put("凌晨", new int[] {0, 6});
        PERIODS.put("清晨", new int[] {5, 8});
        PERIODS.put("早上", new int[] {5, 8});
        PERIODS.put("上午", new int[] {8, 12});
        PERIODS.put("中午", new int[] {11, 13});
        PERIODS.put("午后", new int[] {12, 18});
        PERIODS.put("下午", new int[] {12, 18});
        PERIODS.put("傍晚", new int[] {17, 19});
        PERIODS.put("夜间", new int[] {18, 24});
        PERIODS.put("夜里", new int[] {18, 24});
        PERIODS.put("今晚", new int[] {18, 24});
        PERIODS.put("晚上", new int[] {18, 24});
    }

    private RelativeTimeParser() {
    }

    /**
     * 换算相对时间表达。
     *
     * @param text 原始时间表达（time 槽值）
     * @param now  当前时钟
     * @return {@code start~end} ISO 窗口（本地时区，分钟粒度）；无法识别返回 null
     */
    public static String parse(String text, Clock now) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        LocalDateTime end = LocalDateTime.now(now);
        LocalDateTime start;

        Matcher m = LAST_N.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            start = switch (m.group(2)) {
                case "分钟" -> end.minusMinutes(n);
                case "小时" -> end.minusHours(n);
                default -> end.minusDays(n);
            };
            return window(start, end);
        }
        m = N_AGO.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            start = "小时".equals(m.group(2)) ? end.minusHours(n) : end.minusMinutes(n);
            return window(start, end);
        }
        // 点级优先于时段/整天：同一段文本里「今天下午3点」比「下午」「今天」信息更精确
        LocalDateTime point = parseClockPoint(t, now);
        if (point != null) {
            return window(point.minusMinutes(POINT_RADIUS_MINUTES), point.plusMinutes(POINT_RADIUS_MINUTES));
        }
        // 时段表达（无具体小时）：「昨天下午」= 昨天 12:00~18:00，而不是昨天一整天
        LocalDateTime[] period = parsePeriodWindow(t, now);
        if (period != null) {
            return window(period[0], period[1]);
        }
        LocalDate today = LocalDate.now(now);
        m = DAY_ONLY.matcher(t);
        if (m.find()) {
            LocalDate day = switch (m.group(1)) {
                case "今天" -> today;
                case "昨天" -> today.minusDays(1);
                default -> today.minusDays(2);
            };
            return window(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        }
        if (WEEK.matcher(t).find()) {
            LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return window(weekStart.atStartOfDay(), today.plusDays(1).atStartOfDay());
        }
        return null;
    }

    /**
     * 解析点级时刻：日期成分（今天/昨天/前天 或 M月D日，缺省今天）+ 时段词 + 小时 + 分钟。
     *
     * @param text 原始时间表达
     * @param now  当前时钟
     * @return 具体时刻；表达里没有可识别的「X点」或数值不合法返回 null
     */
    private static LocalDateTime parseClockPoint(String text, Clock now) {
        Matcher clock = CLOCK.matcher(text);
        if (!clock.find()) {
            return null;
        }
        LocalDate day = resolveDay(text, now);
        if (day == null) {
            return null;
        }
        String dayPeriod = clock.group(1);
        String rawHour = clock.group(2) == null ? "" : clock.group(2).trim();
        int hour = parseHour(rawHour, dayPeriod);
        if (hour < 0) {
            return null;
        }
        int minute = parseMinute(clock.group(3));
        if (minute < 0) {
            return null;
        }
        // 「晚上12点」指当天午夜（24:00）→ 次日 0 点；「0点」才是当天凌晨
        if (hour == 12 && isEvening(dayPeriod)) {
            return day.plusDays(1).atTime(0, minute);
        }
        return day.atTime(hour, minute);
    }

    /** @return 是否晚间时段词 */
    private static boolean isEvening(String dayPeriod) {
        return "晚上".equals(dayPeriod) || "今晚".equals(dayPeriod)
                || "夜里".equals(dayPeriod) || "夜间".equals(dayPeriod);
    }

    /**
     * 解析时段表达（无具体小时）：「昨天下午 / 今天上午 / 今晚 / 9月15日晚上」→ 该日时段窗口。
     *
     * @param text 原始时间表达
     * @param now  当前时钟
     * @return [start, end]；无时段词返回 null
     */
    private static LocalDateTime[] parsePeriodWindow(String text, Clock now) {
        for (Map.Entry<String, int[]> period : PERIODS.entrySet()) {
            if (text.contains(period.getKey())) {
                LocalDate day = resolveDay(text, now);
                if (day == null) {
                    return null;
                }
                int startHour = period.getValue()[0];
                int endHour = period.getValue()[1];
                LocalDateTime start = day.atTime(startHour, 0);
                LocalDateTime end = endHour >= 24
                        ? day.plusDays(1).atStartOfDay()
                        : day.atTime(endHour, 0);
                return new LocalDateTime[] {start, end};
            }
        }
        return null;
    }

    /** @return 日期成分；无日期词默认今天，M月D日超出当年该月天数返回 null */
    private static LocalDate resolveDay(String text, Clock now) {
        LocalDate today = LocalDate.now(now);
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.find()) {
            int month = Integer.parseInt(monthDay.group(1));
            int dayOfMonth = Integer.parseInt(monthDay.group(2));
            try {
                return LocalDate.of(today.getYear(), month, dayOfMonth);
            } catch (Exception invalid) {
                return null;
            }
        }
        Matcher dayOnly = DAY_ONLY.matcher(text);
        if (dayOnly.find()) {
            return switch (dayOnly.group(1)) {
                case "今天" -> today;
                case "昨天" -> today.minusDays(1);
                default -> today.minusDays(2);
            };
        }
        return today;
    }

    /**
     * @param rawHour   小时原文（阿拉伯或中文数字）
     * @param dayPeriod 时段词（null = 24 小时制直给）
     * @return 0-23；无法解析返回 -1
     */
    private static int parseHour(String rawHour, String dayPeriod) {
        int hour;
        try {
            hour = Integer.parseInt(rawHour);
        } catch (NumberFormatException notDigits) {
            hour = chineseNumber(rawHour);
        }
        if (hour < 0 || hour > 23) {
            return -1;
        }
        if (dayPeriod == null) {
            return hour;
        }
        boolean afternoon = "午后".equals(dayPeriod) || "下午".equals(dayPeriod)
                || "傍晚".equals(dayPeriod) || isEvening(dayPeriod);
        // 下午/晚上 1-11 点 → +12（下午3点=15、晚上八点=20）；12 点制原值（上午9点=9、中午12点=12）
        return afternoon && hour >= 1 && hour <= 11 ? hour + 12 : hour;
    }

    /** @return 分钟数（「半」=30）；非法返回 -1 */
    private static int parseMinute(String rawMinute) {
        if (rawMinute == null || rawMinute.isBlank()) {
            return 0;
        }
        if ("半".equals(rawMinute.trim())) {
            return 30;
        }
        try {
            int minute = Integer.parseInt(rawMinute.trim());
            return minute >= 0 && minute <= 59 ? minute : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** @return 常用中文数字（一~二十三，「两」=2）；超范围返回 -1 */
    private static int chineseNumber(String raw) {
        return switch (raw) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            case "十三" -> 13;
            case "十四" -> 14;
            case "十五" -> 15;
            case "十六" -> 16;
            case "十七" -> 17;
            case "十八" -> 18;
            case "十九" -> 19;
            case "二十" -> 20;
            case "二十一" -> 21;
            case "二十二" -> 22;
            case "二十三" -> 23;
            default -> -1;
        };
    }

    private static String window(LocalDateTime start, LocalDateTime end) {
        // 截断方向必须对称安全：start 向下（宁早）、end 向上进位到整分（宁晚）。
        // 格式化 HH:mm 丢秒是单向的，end 若随之向下截断，会把「报错刚发生就问」的
        // 最近 60 秒系统性切在窗外（真机 2026-09-21：09:20:26 的报错日志 vs
        // 「最近10分钟」窗口 end=09:20:00，差 26 秒反查未命中）。进位让窗口
        // 最多宽 59 秒，多查不漏查；卡片显示随之多一分钟，用户无感。
        LocalDateTime safeEnd = end.plusMinutes(1).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return start.format(ISO) + "~" + safeEnd.format(ISO);
    }
}
