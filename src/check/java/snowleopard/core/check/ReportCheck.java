package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import snowleopard.core.Version;
import snowleopard.core.names.Names;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.report.Issue;
import snowleopard.core.report.StartupReport;
import snowleopard.core.spi.ProviderLoader;
import snowleopard.core.spi.ToolGroupProvider;

/**
 * M7 验收（2026-10-22 23:58 (UTC+8)）：启动报告 + 注销语义。
 *
 * <p>开发流程 M7 验收点：注销后调用报"工具不存在"而非崩溃；WARN 汇总进报告。
 * 外加：来源分布、重名消歧表、配置行。
 */
public final class ReportCheck {

    private static int pass;
    private static int fail;

    public static final class ModA extends ToolGroupProvider.Simple {
        public ModA() {
            super(Version.CORE_API, "alpha-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("combat", "c", "阿尔法战斗组", "",
                    ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    public static final class ModB extends ToolGroupProvider.Simple {
        public ModB() {
            super(Version.CORE_API, "beta-mod");
        }

        @Override
        public ToolGroupProvider.Descriptor describe() {
            return new ToolGroupProvider.Descriptor("combat", "c", "贝塔战斗组", "",
                    ToolRegistry.Availability.NORMAL, List.of());
        }
    }

    public static void main(String[] args) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        ProviderLoader loader = new ProviderLoader(reg);

        scenarioReport(reg, loader);
        scenarioUnloadSemantics(reg, loader);

        System.out.println("PASS report: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void scenarioReport(ToolRegistry reg, ProviderLoader loader) {
        System.out.println("== A 启动报告：来源分布 + 重名消歧表 ==");
        loader.load(new ModA(), Names.SourceType.MOD);
        loader.load(new ModB(), Names.SourceType.MOD);   // 同短名 combat → 歧义

        List<Issue> issues = new ArrayList<>(loader.issues());
        List<String> rep = StartupReport.render(reg, reg.names(), issues,
                "id 自带 tools-default.json", null);
        String all = String.join("\n", rep);

        check("报告带时间戳", all.contains("(UTC+8)"), rep.get(0));
        check("契约行含 core_api", all.contains("core_api=" + Version.CORE_API), "");
        check("来源分布：mod 2 个", all.contains("mod 2"), all);
        check("modid 列出", all.contains("alpha-mod") && all.contains("beta-mod"), all);
        check("重名消歧表列出 combat 歧义", all.contains("combat →")
                && all.contains("mod:alpha-mod:combat") && all.contains("mod:beta-mod:combat"), all);
        check("配置来源行", all.contains("id 自带 tools-default.json"), all);
    }

    private static void scenarioUnloadSemantics(ToolRegistry reg, ProviderLoader loader) {
        System.out.println("== B 注销：WARN 汇总 + 报「不存在」而非崩溃 ==");
        loader.unload(Names.SourceType.MOD, "beta-mod");
        List<Issue> issues = new ArrayList<>(loader.issues());
        List<String> rep = StartupReport.render(reg, reg.names(), issues, null, null);
        String all = String.join("\n", rep);

        check("WARN 汇总含卸载警告", all.contains("[WARN]") && all.contains("注销"), all);
        check("问题汇总计数含警告", all.contains("警告 1"), all);

        // 注销后调用：不崩溃、报不存在
        String help = reg.help("mod:beta-mod:combat", Map.of());
        check("注销后 help 报「工具不存在」", help != null && help.contains("工具不存在"), help);
        check("注销后 help 不崩溃", true, "");
        List<String> search = reg.search("贝塔", null);
        check("注销后搜索搜不到（不留残影）",
                search.stream().noneMatch(x -> x.contains("beta-mod")), search.toString());
        Names.Resolution resolved = reg.names().resolve("combat", Map.of("combat", "mod:beta-mod:combat"));
        check("指向已注销全名的别名不再解析成功", resolved instanceof Names.Ambiguous
                || resolved instanceof Names.Resolution.NotFound, String.valueOf(resolved));

        // 卸载后消歧消失（只剩一份 combat）
        List<String> rep2 = StartupReport.render(reg, reg.names(), new ArrayList<>(), null, null);
        check("消歧表更新（歧义随注销解除）",
                String.join("\n", rep2).contains("无歧义"), String.join("\n", rep2));
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

    private ReportCheck() {
    }
}
