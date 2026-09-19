package com.jjx.customer.platform.business.ops.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 接口目录（接口槽候选匹配的事实源）：扫描 {@code classpath:agent/schemas/*.json}，
 * 每个文件即一个接口——文件名为接口名，{@code x-title} 中文名、{@code x-aliases}
 * 业务别名（「token获取」「发票冲红」这类用户口中的叫法）。新增接口只需落一个
 * schema 文件，目录、validate_request、候选匹配同时生效。
 *
 * <p>匹配规则（确定性，不走模型，精确优先）：描述命中任一<b>别名</b>（包含匹配，
 * 不分大小写）只取别名结果；无别名命中时用描述中的英文词（≥3 字符）命中接口名兜底
 * ——同族接口（token_issue / token_refresh）此时全部列为候选。供两处消费：</p>
 * <ul>
 *   <li><b>唯一命中</b>：auto-resolve 规则层直接补全 interface 槽（问用户之前自救）；</li>
 *   <li><b>多命中（冲突）</b>：问齐表单注入点选候选，用户点选或手动输入。</li>
 * </ul>
 */
public final class InterfaceCatalog {

    /** 单个接口的目录条目。 */
    public record Iface(String name, String title, List<String> aliases) {
    }

    private static final Logger log = LoggerFactory.getLogger(InterfaceCatalog.class);

    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,}");

    private static final List<Iface> ALL = load();

    private InterfaceCatalog() {
    }

    /** @return 全部接口（文件名序，稳定） */
    public static List<Iface> all() {
        return ALL;
    }

    /**
     * 从用户描述中匹配相关接口（两级：精确优先，模糊兜底）。
     *
     * <ol>
     *   <li><b>别名命中</b>：描述包含某接口的业务别名（「token获取」「发票冲红」）——
     *       用户已给出明确叫法，只取别名命中的接口；</li>
     *   <li><b>词命中兜底</b>：无别名命中时，描述中的英文词（≥3 字符）命中接口名——
     *       「token 失败」这类没说获取还是刷新的，同族接口全部列为候选（冲突 → 用户点选）。</li>
     * </ol>
     *
     * @param text 用户描述（问题/报错/现象拼接均可）
     * @return 相关接口（目录序）；空 = 无匹配
     */
    public static List<Iface> suggest(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Iface> aliasHits = new ArrayList<>();
        for (Iface iface : ALL) {
            boolean hit = iface.aliases().stream()
                    .anyMatch(alias -> normalized.contains(alias.toLowerCase(Locale.ROOT)));
            if (hit) {
                aliasHits.add(iface);
            }
        }
        if (!aliasHits.isEmpty()) {
            return aliasHits;
        }
        List<String> words = englishWords(normalized);
        List<Iface> wordHits = new ArrayList<>();
        for (Iface iface : ALL) {
            String name = iface.name().toLowerCase(Locale.ROOT);
            if (words.stream().anyMatch(name::contains)) {
                wordHits.add(iface);
            }
        }
        return wordHits;
    }

    private static List<String> englishWords(String text) {
        List<String> words = new ArrayList<>();
        var matcher = ENGLISH_WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static List<Iface> load() {
        List<Iface> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:agent/schemas/*.json");
            ObjectMapper mapper = new ObjectMapper();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".json")) {
                    continue;
                }
                String name = filename.substring(0, filename.length() - ".json".length());
                try (InputStream in = resource.getInputStream()) {
                    JsonNode node = mapper.readTree(in);
                    String title = node.path("x-title").asText(name);
                    List<String> aliases = new ArrayList<>();
                    node.path("x-aliases").forEach(alias -> {
                        String value = alias.asText("");
                        if (!value.isBlank()) {
                            aliases.add(value.trim());
                        }
                    });
                    loaded.add(new Iface(name, title, List.copyOf(aliases)));
                }
            }
            loaded.sort(java.util.Comparator.comparing(Iface::name));
            log.info("[interface-catalog] 接口目录加载：{} 个（{})", loaded.size(),
                    loaded.stream().map(Iface::name).toList());
        } catch (Exception e) {
            // 目录不可用只影响候选匹配（回退手动输入），不让诊断链路启动失败
            log.warn("[interface-catalog] 接口目录加载失败（候选匹配退化为手动输入）：{}", e.getMessage());
        }
        return List.copyOf(loaded);
    }

    /** @return 候选的可读对照（问齐 hint 用）：{@code token_issue=获取访问令牌、…} */
    public static String describe(List<Iface> candidates) {
        String[] parts = candidates.stream()
                .map(iface -> iface.name() + "=" + iface.title())
                .toArray(String[]::new);
        return String.join("、", parts);
    }
}
