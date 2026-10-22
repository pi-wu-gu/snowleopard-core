package snowleopard.core.check;

import java.util.List;
import java.util.Map;

import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.registry.ToolRegistry.Availability;
import snowleopard.core.registry.ToolRegistry.Group;
import snowleopard.core.registry.ToolRegistry.Tool;
import snowleopard.core.names.Names;
import snowleopard.core.report.Issue;

/**
 * M2 验收（2026-10-22 22:39 (UTC+8)）：注册表 + 注入/查询/搜索。
 * 对应开发流程 M2 验收：注入体积断言（组简介注入、详解不注入）；搜索命中/来源过滤；坏清单拒载留 ERROR。
 */
public final class RegistryCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new Group(new Names.Qualified(Names.SourceType.BUILTIN, "core", "memory"),
                "builtin/memory", "记忆读写与压缩", "详解：双根存储、惰性压缩、稀疏性约束……",
                Availability.ALWAYS_ON,
                List.of(new Tool("read", "读记忆", "memory_read(level, period)",
                        Map.of("level", "string", "period", "string")),
                        new Tool("repair", "", "memory_repair()",
                                Map.of()))));
        reg.register(new Group(new Names.Qualified(Names.SourceType.MOD, "example", "combat"),
                "combat/melee", "示例战斗工具", "模组侧示例详解",
                Availability.NORMAL,
                List.of(new Tool("attack", "攻击", "attack(target)", Map.of("target", "string")))));
        reg.register(new Group(new Names.Qualified(Names.SourceType.MOD, "example", "secret"),
                "", "隐藏工具组", "secret-details-should-not-leak",
                Availability.DEFAULT_OFF,
                List.of(new Tool("peek", "偷看", "peek()", Map.of()))));

        // ① 注入：组简介进上下文、详解不进
        List<String> briefs = reg.briefs();
        String all = String.join("\n", briefs);
        check("注入含组简介", all.contains("记忆读写与压缩"), all);
        check("注入不含详解（省 token 的分界）", !all.contains("双根存储") && !all.contains("详解"),
                all);
        check("注入含分类首行", all.contains("[分类: builtin/memory]"), all);
        check("DEFAULT_OFF 组注入标注仅名称", all.contains("仅名称"), all);

        // ② 查询：详解按需、禁用只回名称、无简介回退
        String help = reg.help("memory", Map.of());
        check("help 给详解（含参数表）", help != null && help.contains("参数:") && help.contains("详解"),
                String.valueOf(help));
        check("help 无简介工具回退提示", help != null && help.contains("（无简介，看详解）"),
                String.valueOf(help));
        String offHelp = reg.help("secret", Map.of());
        check("禁用组 help 只回名称", offHelp != null && offHelp.contains("仅名称")
                        && !offHelp.contains("secret-details-should-not-leak"),
                String.valueOf(offHelp));
        Object amb = reg.help("combat", Map.of("combat", "mod:example:combat"));
        check("help 支持别名消歧", amb != null && amb.toString().contains("attack(target)"),
                String.valueOf(amb));

        // ③ 搜索：命中/来源过滤/禁用不泄详解
        List<String> s1 = reg.search("压缩", "");
        check("搜索命中简介", s1.stream().anyMatch(x -> x.contains("memory")), s1.toString());
        List<String> s2 = reg.search("", "example");
        check("来源过滤（sourceId）", s2.stream().allMatch(x -> x.contains("example"))
                        && s2.stream().noneMatch(x -> x.contains("builtin:core")),
                s2.toString());
        List<String> s3 = reg.search("secret-details", "");
        check("禁用组详解搜不到（只搜名字）", s3.isEmpty(), s3.toString());
        List<String> s4 = reg.search("secret", "");
        check("禁用组名字搜得到", s4.stream().anyMatch(x -> x.contains("secret") && x.contains("仅名称")),
                s4.toString());

        // ④ 坏清单拒载留 ERROR
        ToolRegistry bad = new ToolRegistry();
        boolean r1 = bad.loadManifest("{\"group\":\"nogroup_summary\",\"tools\":[]}",
                Names.SourceType.MOD, "m");
        boolean r2 = bad.loadManifest("{broken json", Names.SourceType.MOD, "m");
        boolean r3 = bad.loadManifest(
                "{\"group\":\"g\",\"summary\":\"s\",\"tools\":[{\"name\":\"t\",\"usage\":\"u\"}]}",
                Names.SourceType.MOD, "m");
        check("无简介组拒载", !r1, "接受了");
        check("坏 JSON 拒载", !r2, "接受了");
        check("缺 params 的工具拒载（详解=usage+params）", !r3, "接受了");
        check("拒载留 ERROR 记录", bad.issues().stream()
                        .filter(i -> i.level() == Issue.Level.ERROR).count() == 3,
                bad.issues().toString());

        // ⑤ ALWAYS_ON 不可禁用
        boolean disabled = reg.setEnabled("builtin:core:memory", false);
        check("ALWAYS_ON 禁用被拒", !disabled && reg.isEnabled("builtin:core:memory"),
                String.valueOf(disabled));
        reg.setEnabled("mod:example:combat", false);
        check("NORMAL 可禁用", !reg.isEnabled("mod:example:combat"), "still on");

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " registry: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " —— " + detail);
        }
    }

    private RegistryCheck() {
    }
}
