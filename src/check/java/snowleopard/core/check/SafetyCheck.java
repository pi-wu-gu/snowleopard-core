package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import snowleopard.core.names.Names;
import snowleopard.core.perm.Permissions;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.registry.ToolRegistry.Availability;
import snowleopard.core.registry.ToolRegistry.Group;
import snowleopard.core.registry.ToolRegistry.Tool;
import snowleopard.core.report.Issue;
import snowleopard.core.result.Result;
import snowleopard.core.trigger.TriggerRegistry;
import snowleopard.core.trigger.TriggerRegistry.Hit;
import snowleopard.core.trigger.TriggerRegistry.Hook;
import snowleopard.core.trigger.TriggerRegistry.Point;

/**
 * M3 验收（2026-10-22 22:46 (UTC+8)）：权限 + 结果四级 + 触发。
 * 对应开发流程 M3 验收：权限三档各一发（含"禁内置组被拒"）；热更新不回溯；
 * 触发顺序确定性；SEVERE 不出现在内置工具输出（真"带伤运行"场景除外）。
 */
public final class SafetyCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path tmp = root.resolve("build/m3-tmp");
        Files.createDirectories(tmp);

        results();
        perms(tmp);
        triggers();

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " safety: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 结果四级：非 OK 强制描述。 */
    private static void results() {
        check("OK 正常", Result.ok("x") instanceof Result.Ok, "not ok");
        check("WARN 带描述可建", Result.warn("x", "有点影响") instanceof Result.Warn, "not warn");
        check("SEVERE 带描述可建", Result.severe("part", "严重但仍运作") instanceof Result.Severe, "no");
        check("ERROR 带描述可建", Result.error("显而易见") instanceof Result.Error, "no");
        boolean w = false;
        try {
            Result.warn("x", " ");
        } catch (IllegalArgumentException e) {
            w = true;
        }
        check("WARN 无描述被拒（构造期强制）", w, "未抛");
        boolean e1 = false;
        try {
            Result.error(null);
        } catch (IllegalArgumentException e) {
            e1 = true;
        }
        check("ERROR 无描述被拒", e1, "未抛");
        boolean s = false;
        try {
            Result.severe("p", "");
        } catch (IllegalArgumentException e) {
            s = true;
        }
        check("SEVERE 无描述被拒", s, "未抛");
    }

    /** 权限：三档、ALWAYS_ON 护栏、热更新不回溯、namesOnly。 */
    private static void perms(Path tmp) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new Group(new Names.Qualified(Names.SourceType.BUILTIN, "core", "memory"),
                "", "记忆", "d", Availability.ALWAYS_ON,
                List.of(new Tool("read", "读", "read()", Map.of()))));
        reg.register(new Group(new Names.Qualified(Names.SourceType.MOD, "example", "combat"),
                "", "战斗", "d", Availability.NORMAL,
                List.of(new Tool("attack", "攻", "attack()", Map.of()))));
        reg.register(new Group(new Names.Qualified(Names.SourceType.MOD, "example", "secret"),
                "", "隐藏", "d", Availability.DEFAULT_OFF,
                List.of(new Tool("peek", "看", "peek()", Map.of()))));

        // ① 默认启动 = 全权限（ID0）
        Permissions def = Permissions.defaultAll(reg);
        check("① 默认启动全权限（含 DEFAULT_OFF 也全开？否——按注册态）",
                def.table().allows("builtin:core:memory", "read")
                        && def.table().allows("mod:example:combat", "attack"),
                def.table().toString());

        // ② 指定 ID：权限文件（input 优先）
        Path bundled = tmp.resolve("perm-bundled.json");
        Files.writeString(bundled, """
                {"groups": {"builtin:core:memory": {"tools": "*"}}}
                """);
        Path input = tmp.resolve("perm-input.json");
        Files.writeString(input, """
                {"groups": {
                  "builtin:core:memory": {"enabled": true, "tools": "*"},
                  "mod:example:combat": {"enabled": true, "tools": {"attack": true}},
                  "mod:example:secret": {"enabled": true, "tools": {"peek": true}},
                  "mod:example:memory": {"enabled": true, "tools": "*"}
                }}
                """);
        Permissions p = Permissions.fromFiles(reg, input, bundled);
        check("② input 优先于 bundled（combat 出现 = 来自 input）",
                p.table().allows("mod:example:combat", "attack"), p.table().source());
        check("② 匹配失败=WARN（mod:example:memory 不存在）",
                p.table().issues().stream().anyMatch(i -> i.level() == Issue.Level.WARN
                        && i.where().equals("mod:example:memory")), p.table().issues().toString());
        check("② namesOnly：未配置的组仅可见名称",
                p.namesOnly("mod:other:x", "t") && !p.namesOnly("builtin:core:memory", "read"),
                "namesOnly 语义错");

        // ③ ALWAYS_ON 禁用被拒（"禁内置组被拒"）
        Path bad = tmp.resolve("perm-bad.json");
        Files.writeString(bad, """
                {"groups": {"builtin:core:memory": {"enabled": false, "tools": "*"}}}
                """);
        Permissions pBad = Permissions.fromFiles(reg, bad, null);
        check("③ ALWAYS_ON 禁用 = 配置失败（ERROR）+ 仍启用",
                pBad.table().issues().stream().anyMatch(i -> i.level() == Issue.Level.ERROR
                                && i.message().contains("ALWAYS_ON"))
                        && pBad.table().allows("builtin:core:memory", "read"),
                pBad.table().issues().toString());

        // ④ 热更新不回溯：旧表快照不变、新表生效
        Permissions.Table old = p.table();
        Path input2 = tmp.resolve("perm-input2.json");
        Files.writeString(input2, """
                {"groups": {"mod:example:combat": {"enabled": false, "tools": "*"}}}
                """);
        Permissions.Table now = p.reload(input2, bundled);
        check("④ 热更新后新表生效（combat 被禁）", !now.allows("mod:example:combat", "attack"),
                now.toString());
        check("④ 旧表快照不回溯（在执行的调用不受影响）",
                old.allows("mod:example:combat", "attack"), "旧表被改写");
    }

    /** 触发：确定序、异常不杀主链。 */
    private static void triggers() {
        TriggerRegistry t = new TriggerRegistry();
        List<String> order = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger();
        Hook a = new Hook() {
            public String groupFull() {
                return "mod:z:aaa";
            }

            public void fire(Point p) {
                order.add("aaa");
            }
        };
        Hook b = new Hook() {
            public String groupFull() {
                return "builtin:core:memory";
            }

            public int priority() {
                return -1; // 小者先
            }

            public void fire(Point p) {
                order.add("mem");
            }
        };
        Hook badHook = new Hook() {
            public String groupFull() {
                return "mod:m:bad";
            }

            public void fire(Point p) {
                errors.incrementAndGet();
                throw new IllegalStateException("故意炸");
            }
        };
        Hook c = new Hook() {
            public String groupFull() {
                return "mod:z:ccc";
            }

            public void fire(Point p) {
                order.add("ccc");
            }
        };
        t.register(a);
        t.register(b);
        t.register(badHook);
        t.register(c);

        List<Hit> hits = t.fire(Point.TURN_END);
        check("确定序：priority 小者先、同级按组全名字典序",
                order.equals(List.of("mem", "aaa", "ccc")), order.toString());
        check("异常钩子不杀主链（4 个钩子都跑了）",
                order.size() == 3 && errors.get() == 1 && hits.size() == 4,
                order + "/" + errors + "/" + hits.size());
        check("异常钩子记 SEVERE（带伤运行：坏了但整轮继续）",
                hits.stream().anyMatch(h -> h.groupFull().equals("mod:m:bad")
                        && h.result() instanceof Result.Severe), hits.toString());
        check("正常钩子记 OK", hits.stream().anyMatch(h -> h.result() instanceof Result.Ok),
                hits.toString());
        Map<Point, List<String>> ord = t.order();
        check("order() 与 fire 一致", ord.get(Point.TURN_END).equals(List.of(
                "builtin:core:memory", "mod:m:bad", "mod:z:aaa", "mod:z:ccc")),
                ord.toString());
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

    private SafetyCheck() {
    }
}
