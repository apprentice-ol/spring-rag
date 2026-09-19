package com.agentframework.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.engine.pluginruntime.PluginDescriptor;
import com.agentframework.engine.pluginruntime.PluginRuntime;
import com.agentframework.extension.manifest.Isolation;
import com.agentframework.extension.manifest.PluginManifest;
import com.agentframework.extension.manifest.PluginManifestReader;
import com.agentframework.extension.permission.PermissionChecker;
import com.agentframework.extension.permission.PermissionDeniedException;
import com.agentframework.extension.permission.PermissionSet;
import com.agentframework.extension.permission.Quota;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.extension.permission.QuotaExceededException;
import com.agentframework.extension.registry.ApiVersion;
import com.agentframework.extension.registry.DefaultExtensionRegistry;
import com.agentframework.extension.registry.ExtensionMeta;
import com.agentframework.extension.spi.ConfigFileSpi;
import com.agentframework.extension.spi.PluginPackageSpi;
import com.agentframework.extension.spi.SpiEntry;
import com.agentframework.extension.spi.SpiRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 扩展层测试：注册表、SPI、插件清单与权限配额。 */
class ExtensionLayerTest {

    /** 测试用扩展点。 */
    public interface Greeter {

        String greet(String name);
    }

    /** 测试用扩展实现，需保留无参构造以便反射实例化。 */
    public static class HelloGreeter implements Greeter {

        @Override
        public String greet(String name) {
            return "hello " + name;
        }
    }

    @Test
    @DisplayName("注册表支持注册、解析、列举与注销")
    void registryLifecycle() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(Greeter.class, "hello", new HelloGreeter());

        assertEquals("hello world", registry.resolve(Greeter.class, "hello").greet("world"));
        assertEquals(List.of("hello"), registry.ids(Greeter.class));
        assertEquals(1, registry.list(Greeter.class).size());
        assertNotNull(registry.meta(Greeter.class, "hello"));
        assertTrue(registry.unregister(Greeter.class, "hello"));
        assertTrue(registry.tryResolve(Greeter.class, "hello").isEmpty());
    }

    @Test
    @DisplayName("重复注册同名扩展会立即失败")
    void duplicateRegistrationRejected() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(Greeter.class, "hello", new HelloGreeter());
        assertThrows(IllegalStateException.class,
                () -> registry.register(Greeter.class, "hello", new HelloGreeter()));
    }

    @Test
    @DisplayName("API 版本不兼容时拒绝注册")
    void versionCheck() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(new ApiVersion(1, 0, 0));
        assertEquals(new ApiVersion(1, 0, 0), registry.apiVersion());
        assertTrue(ApiVersion.CURRENT.compatibleWith(ApiVersion.parse("1.0.0")));
        assertFalse(ApiVersion.CURRENT.compatibleWith(ApiVersion.parse("2.0.0")));
        assertThrows(IllegalStateException.class, () -> registry.versionCheck("2.0.0"));
    }

    @Test
    @DisplayName("配置文件 SPI 可加载并注册扩展实现")
    void configFileSpi(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("plugin.properties");
        Files.writeString(config, """
                plugin.id=sample
                plugin.version=1.0.0
                %s#hello=%s
                """.formatted(Greeter.class.getName(), HelloGreeter.class.getName()));

        List<SpiEntry> entries = new ConfigFileSpi(config, getClass().getClassLoader()).load();
        assertEquals(1, entries.size());

        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        SpiRegistrar.register(registry, entries.get(0));
        assertEquals("hello codex", registry.resolve(Greeter.class, "hello").greet("codex"));
    }

    @Test
    @DisplayName("插件清单可解析隔离级别、权限与扩展点")
    void pluginManifestParsing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("plugin.properties"), """
                plugin.id=weather
                plugin.version=2.1.0
                plugin.apiVersion=1.0.0
                plugin.isolation=PROCESS
                plugin.permissions=network,tool
                plugin.extensionPoints=ToolProvider
                plugin.dependencies=geo@1.0.0
                plugin.config.city=默认城市
                """);

        PluginManifest manifest = PluginManifestReader.read(dir);
        assertEquals("weather", manifest.id());
        assertEquals(Isolation.PROCESS, manifest.isolation());
        assertEquals(List.of("network", "tool"), manifest.permissions());
        assertEquals("默认城市", manifest.configSchema().get("city"));
        assertEquals("weather@2.1.0", manifest.key());
    }

    @Test
    @DisplayName("插件运行时按权限与隔离级别决定是否加载")
    void pluginRuntimeEnforcesPermissions(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("plugin.properties"), """
                plugin.id=plugin-a
                plugin.version=1.0.0
                plugin.isolation=PROCESS
                plugin.permissions=network
                """);

        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        PluginRuntime denied = new PluginRuntime(registry, new PermissionChecker(PermissionSet.none()));
        PluginDescriptor deniedDescriptor = denied.load(dir);
        assertFalse(deniedDescriptor.loaded());
        assertTrue(deniedDescriptor.error().contains("权限"));

        PluginRuntime granted = new PluginRuntime(registry,
                new PermissionChecker(PermissionSet.of("network")));
        PluginDescriptor grantedDescriptor = granted.load(dir);
        assertEquals("plugin-a", grantedDescriptor.manifest().id());
        assertTrue(grantedDescriptor.loaded());
    }

    @Test
    @DisplayName("权限集合按最小授权求交集")
    void permissionSetIntersection() {
        PermissionSet requested = PermissionSet.of("network", "file.write", "model");
        PermissionSet granted = PermissionSet.of("network", "model");

        PermissionSet effective = requested.intersect(granted);
        assertTrue(effective.allows("network"));
        assertFalse(effective.allows("file.write"));
        assertTrue(granted.allowsAll(List.of("network")));
    }

    @Test
    @DisplayName("权限校验器在缺少权限时抛出异常")
    void permissionCheckerThrows() {
        PermissionChecker checker = new PermissionChecker(PermissionSet.of("network"));
        assertThrows(PermissionDeniedException.class, () -> checker.require("file.write", "plugin-a"));
        checker.require("network", "plugin-a");
        assertInstanceOf(PermissionSet.class, checker.effective(PermissionSet.of("network", "tool")));
    }

    @Test
    @DisplayName("配额执行器累计用量并在超限时中断")
    void quotaEnforcer() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        Quota quota = Quota.of(100, 2, 1);

        enforcer.consumeTokens("s1", 40, quota);
        enforcer.consumeNode("s1", quota);
        enforcer.consumeToolCall("s1", quota);
        assertEquals(40, enforcer.usage("s1").tokens());
        assertEquals(1, enforcer.usage("s1").toolCalls());

        assertThrows(QuotaExceededException.class, () -> enforcer.consumeTokens("s1", 100, quota));
        assertThrows(QuotaExceededException.class, () -> enforcer.consumeToolCall("s1", quota));
        enforcer.reset("s1");
        assertEquals(0, enforcer.usage("s1").tokens());
    }

    @Test
    @DisplayName("插件包 SPI 能按目录约定加载扩展")
    void pluginPackageSpi(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("plugin.properties"), """
                plugin.id=sample-package
                plugin.version=1.0.0
                %s#pkg=%s
                """.formatted(Greeter.class.getName(), HelloGreeter.class.getName()));

        List<SpiEntry> entries = new PluginPackageSpi(dir).load();
        assertEquals(1, entries.size());
        assertEquals("pkg", entries.get(0).meta().id());
        assertEquals("demo", new Extensions().meta().id());
    }

    /** 校验扩展元信息默认值。 */
    private static final class Extensions {

        ExtensionMeta meta() {
            return ExtensionMeta.of("demo", "Guards");
        }
    }
}
