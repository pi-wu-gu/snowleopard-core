package snowleopard.core.check;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import snowleopard.core.Version;
import snowleopard.core.names.Names;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.spi.ProviderLoader;
import snowleopard.core.spi.ToolGroupProvider;

/**
 * M6 验收（2026-10-22 23:36 (UTC+8)）：Provider SPI + 来源标注 + 模组卸载注销。
 *
 * <p>对应开发流程 M6 验收点：外部测试工具组经 SPI 注册且带来源；core_api 不匹配拒载。
 * 外加：来源空拒载、按来源注销（卸载重装）、ServiceLoader 发现、同全名重名拒载。
 * （jdeps 零 MC 由 smoke 每轮把关，不在此重复。）
 */
public final class ShellCheck {

    private static int pass;
    private static int fail;

    /** 外部测试工具组 A（经 SPI 接入，来源 example-mod）。 */
    public static final class TestModA extends ToolGroupProvider.Simple {
        public TestModA() {
            super(Version.CORE_API, "example-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("combat", "combat",
                    "战斗工具组（测试）", "由外部 provider 提供",
                    ToolRegistry.Availability.NORMAL,
                    List.of(new ToolRegistry.Tool("attack", "攻击", "attack(target)", Map.of())));
        }
    }

    /** 契约号故意过期的 provider。 */
    public static final class StaleApi extends ToolGroupProvider.Simple {
        public StaleApi() {
            super(Version.CORE_API + 1, "stale-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("x", "x", "s", "",
                    ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    /** 来源为空的 provider。 */
    public static final class NoSource extends ToolGroupProvider.Simple {
        public NoSource() {
            super(Version.CORE_API, "  ");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("y", "y", "s", "",
                    ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    /** 同全名的后来者（重名拒载）。 */
    public static final class Dup extends ToolGroupProvider.Simple {
        public Dup() {
            super(Version.CORE_API, "example-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("combat", "combat",
                    "同名组（应被拒）", "", ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    /** ServiceLoader 发现用。 */
    public static final class Discovered extends ToolGroupProvider.Simple {
        public Discovered() {
            super(Version.CORE_API, "discovered-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("vision", "sense",
                    "被发现的工具组", "", ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    public static void main(String[] args) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        ProviderLoader loader = new ProviderLoader(reg);

        scenarioLoadWithSource(reg, loader);
        scenarioRejects(loader, reg);
        scenarioUnload(loader, reg);
        scenarioServiceLoader(reg);
        scenarioDuplicate(reg);

        System.out.println("PASS shell: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void scenarioLoadWithSource(ToolRegistry reg, ProviderLoader loader) {
        System.out.println("== A 外部工具组经 SPI 注册且带来源 ==");
        String full = loader.load(new TestModA(), Names.SourceType.MOD);
        check("装载成功返回限定名", "mod:example-mod:combat".equals(full), String.valueOf(full));
        check("限定名由装载器拼（类型:来源:组）", full != null && full.startsWith("mod:example-mod:"),
                String.valueOf(full));
        check("注册表里带来源标注", reg.groups().stream()
                .anyMatch(g -> g.full().equals("mod:example-mod:combat")), "");
        check("briefs 可见", reg.briefs().stream().anyMatch(b -> b.contains("mod:example-mod:combat")),
                reg.briefs().toString());
        check("来源过滤可搜到", reg.search("战斗", "example-mod").size() == 1,
                reg.search("战斗", "example-mod").toString());
    }

    private static void scenarioRejects(ProviderLoader loader, ToolRegistry reg) {
        System.out.println("== B core_api 不匹配拒载 + 来源空拒载 ==");
        String r1 = loader.load(new StaleApi(), Names.SourceType.MOD);
        check("core_api 不匹配 → 拒载", r1 == null, String.valueOf(r1));
        check("拒载留 ERROR（含 core_api 字样）", loader.issues().stream()
                .anyMatch(i -> i.toString().contains("core_api")), loader.issues().toString());

        String r2 = loader.load(new NoSource(), Names.SourceType.MOD);
        check("来源空 → 拒载", r2 == null, String.valueOf(r2));
        check("拒载留 ERROR（来源标注）", loader.issues().stream()
                .anyMatch(i -> i.toString().contains("来源")), "");
        check("被拒的没有进注册表", reg.groups().stream()
                .noneMatch(g -> g.full().contains("stale") || g.full().contains(":y")), "");
    }

    private static void scenarioUnload(ProviderLoader loader, ToolRegistry reg) {
        System.out.println("== C 模组卸载注销 + 重装 ==");
        check("卸载返回被删清单", loader.unload(Names.SourceType.MOD, "example-mod")
                .contains("mod:example-mod:combat"), "");
        check("组从注册表消失", reg.groups().stream()
                .noneMatch(g -> g.full().equals("mod:example-mod:combat")), "");
        check("短名释放（resolve 不再指向它）",
                reg.names().resolve("combat", Map.of()) instanceof Names.Resolution.NotFound, "");

        // 卸载重装是正常生命周期：同全名可以再注册
        String again = loader.load(new TestModA(), Names.SourceType.MOD);
        check("重装成功（注销释放了全名）", "mod:example-mod:combat".equals(again), String.valueOf(again));
        check("幂等：再卸载不存在的来源返回空",
                loader.unload(Names.SourceType.MOD, "never-there").isEmpty(), "");
    }

    private static void scenarioServiceLoader(ToolRegistry reg) throws Exception {
        System.out.println("== D ServiceLoader 自动发现 ==");
        Path tmp = Files.createTempDirectory("sl-shell-svc");
        Path svc = tmp.resolve("META-INF/services");
        Files.createDirectories(svc);
        Files.writeString(svc.resolve("snowleopard.core.spi.ToolGroupProvider"),
                Discovered.class.getName());
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{tmp.toUri().toURL()}, ShellCheck.class.getClassLoader())) {
            ProviderLoader l2 = new ProviderLoader(reg);
            List<String> got = l2.loadDiscovered(Names.SourceType.MOD, cl);
            check("ServiceLoader 发现并装载", got.contains("mod:discovered-mod:vision"),
                    got.toString());
            check("来源标注为 mod:<modid>", reg.groups().stream()
                    .anyMatch(g -> g.full().equals("mod:discovered-mod:vision")), "");
        }
    }

    private static void scenarioDuplicate(ToolRegistry reg) {
        System.out.println("== E 同全名重名拒载（方案 D：先到者保留）==");
        ProviderLoader l3 = new ProviderLoader(reg);
        reg.unregisterSource(Names.SourceType.MOD, "example-mod");   // 清掉 C 段的重装，状态干净
        check("第一份装载成功", l3.load(new TestModA(), Names.SourceType.MOD) != null, "");
        String dup = l3.load(new Dup(), Names.SourceType.MOD);   // 同全名
        check("后者拒载", dup == null, String.valueOf(dup));
        check("先到者保留（简介没被覆盖）", reg.help("mod:example-mod:combat", Map.of())
                .contains("外部 provider"), reg.help("combat", Map.of()));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    private ShellCheck() {
    }
}
