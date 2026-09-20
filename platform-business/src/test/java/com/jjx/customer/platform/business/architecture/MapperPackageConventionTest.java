package com.jjx.customer.platform.business.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 包约定守卫：{@code *.mapper} 包里只允许放 MyBatis mapper 接口。
 *
 * <p><b>这条约定为什么必须守</b>：{@code @MapperScan("com.jjx.customer.platform.**.mapper")}
 * 是<b>按包</b>注册的——该包下<b>每个接口</b>都会成为 mapper bean，<b>与是否标 {@code @Mapper} 无关</b>。
 * 于是把一个"端口接口"（如 {@code TaskReaperStore}）误放进 mapper 包，会让它凭空多出一个实现，
 * 注入时报：</p>
 *
 * <pre>
 * Parameter 0 of constructor in …TaskReaper required a single bean, but 2 were found:
 *     - agentTaskMapper: …/task/mapper/AgentTaskMapper.class
 *     - taskReaperStore: …/task/mapper/TaskReaperStore.class
 * </pre>
 *
 * <p><b>真实踩过</b>（2026-09-19）：本轮 115 项单测全绿、编译通过，但<b>应用起不来</b>。
 * 原因是 Spring 的 bean 歧义只在 {@code refresh()} 时暴露，而全仓唯一的装配测试
 * （{@code ChatOrchestratorWiringTest}）用的是最小上下文，覆盖不到新增的注入点。</p>
 *
 * <p>因此这里改用<b>源码级</b>断言：不依赖启动上下文，也不需要能加载所有模块的 classpath——
 * 直接遍历仓库源码，凡是 {@code mapper} 目录下的接口声明，都必须是 mapper。
 * 越界的类会被点名，而不是等到某次启动才炸。</p>
 */
class MapperPackageConventionTest {

    /** 接口声明（含修饰符）。 */
    private static final Pattern INTERFACE = Pattern.compile("\\binterface\\s+(\\w+)");

    /** mapper 的特征：继承 MyBatis-Plus 的 BaseMapper。 */
    private static final Pattern MAPPER = Pattern.compile("\\bBaseMapper\\s*<");

    @Test
    void mapper包下的接口必须都继承BaseMapper() throws IOException {
        Optional<Path> root = repoRoot();
        assumeTrue(root.isPresent(), "非源码检出（找不到含 <modules> 的父 pom），跳过包约定检查");

        List<String> offenders = new ArrayList<>();
        for (Path file : mapperPackageSources(root.get())) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            boolean isInterface = INTERFACE.matcher(source).find();
            boolean isMapper = MAPPER.matcher(source).find();
            if (isInterface && !isMapper) {
                offenders.add(root.get().relativize(file).toString().replace('\\', '/'));
            }
        }

        assertTrue(offenders.isEmpty(),
                "mapper 包（*.mapper）里只允许放 MyBatis mapper 接口——@MapperScan 会把该包下每个接口"
                        + "都注册成 bean，放端口接口进去会让它凭空多出一个实现，启动时报"
                        + "「required a single bean, but 2 were found」。\n"
                        + "请把下列接口移出 mapper 包（它应该是端口/契约，不是 mapper）：\n  "
                        + String.join("\n  ", offenders));
    }

    /** 父目录名为 mapper 的 Java 文件。 */
    private static boolean isInMapperPackage(Path file) {
        Path parent = file.getParent();
        return parent != null && "mapper".equals(parent.getFileName().toString());
    }

    /**
     * 收集所有 Maven 模块下 {@code src} 里的「mapper 包」Java 源文件。
     *
     * <p><b>只走 {@code platform-★ / src}</b>，不扫全仓：仓库里还有 {@code .git}（百 MB 级）
     * 与 {@code node_modules}（数千条目），全量遍历既慢又毫无意义——Java 源码只可能在
     * 模块的 {@code src} 下，顺带也天然避开了 {@code target/} 构建产物。</p>
     */
    private static List<Path> mapperPackageSources(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("platform-"))
                    .toList()) {
                Path src = module.resolve("src");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(src)) {
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".java"))
                            .filter(MapperPackageConventionTest::isInMapperPackage)
                            .forEach(found::add);
                }
            }
        }
        return found;
    }

    /** 从工作目录向上找到含 {@code <modules>} 的父 pom（即仓库根）。 */
    private static Optional<Path> repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            try {
                if (Files.readString(pom, StandardCharsets.UTF_8).contains("<modules>")) {
                    return Optional.of(dir);
                }
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
