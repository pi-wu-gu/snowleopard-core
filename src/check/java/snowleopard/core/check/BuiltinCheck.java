package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import snowleopard.core.SnowLeopard;
import snowleopard.core.builtin.BuiltinGroups;
import snowleopard.core.builtin.BuiltinToolkit;
import snowleopard.core.registry.ToolExposure;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.registry.ToolRegistry.Availability;
import snowleopard.core.result.Result;

/**
 * 内置六组验收（2026-10-23 19:30 (UTC+8) 起，2026-10-24 05:50 改口径）：注册面 + 执行面 + 隔离 + 权限默认。
 *
 * <p>0.4.0 是四组 18 工具（会话/记忆/任务/笔记）；0.5.0 起加协作面两组（唤醒 4 / 群组 10）= 六组 32 工具。
 * 断言名里的"四组"是 0.4.0 的残留，本轮改成六组——**断言名是规格的可读投影，名字错了
 * 人照着名字改文档就会接着错**（2026-10-24 审查抓到）。
 *
 * <p>判据（都是"接进注入面"的确切形态，不做宽泛扫）：
 * <ol>
 *   <li><b>注册面</b>：bootstrap 后恰 4 组 {@code builtin:core:*}、可用性三档正确、共 18 工具、
 *       {@code briefs()} 进上下文且<b>不含详解</b>、{@code help} 给 usage + 参数；</li>
 *   <li><b>执行面</b>：每个工具真的落到 Store（磁盘有变化），不是只回 Ok 文案；</li>
 *   <li><b>两段式</b>：note_delete / memory_correct 首段不落盘、带令牌才生效；</li>
 *   <li><b>失败形态</b>：模型输入错 → {@link Result.Error}（带可用清单提示）；
 *       宿主路由错（未知组）→ {@link IllegalArgumentException}——两者不许混为一谈；</li>
 *   <li><b>红线 3</b>：两个调用 ID 的 todo/notes 互不可见；</li>
 *   <li><b>权限默认</b>：文件模式没提 builtin 组 → 按自带默认开；显式关（NORMAL）→ 真的关。</li>
 * </ol>
 */
public final class BuiltinCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path tmp = Files.createTempDirectory("sl-builtin");

        SnowLeopard core = SnowLeopard.bootstrap(tmp, SnowLeopard.Options.allowAll());
        ToolRegistry reg = core.registry();

        // ── ① 注册面 ───────────────────────────────────────────────
        System.out.println("== 1 注册面（注入/查询） ==");
        check("七组全注册（恰 7）", reg.groups().size() == 7, "实际 " + reg.groups().size());
        for (String g : BuiltinGroups.GROUP_NAMES) {
            String full = "builtin:core:" + g;
            check("注册含 " + full, reg.groups().stream().anyMatch(x -> x.full().equals(full)),
                    reg.groups().toString());
        }
        check("会话/记忆 ALWAYS_ON（不可禁用）",
                avail(reg, "builtin:core:session") == Availability.ALWAYS_ON
                        && avail(reg, "builtin:core:memory") == Availability.ALWAYS_ON, "");
        check("任务/笔记 NORMAL（可关）",
                avail(reg, "builtin:core:todo") == Availability.NORMAL
                        && avail(reg, "builtin:core:notes") == Availability.NORMAL, "");
        check("工具总数 35（四基础组 18 + 唤醒 4 + 群组 10 + 目录 3）", reg.toolCount() == 35,
                "实际 " + reg.toolCount());
        List<String> briefs = reg.briefs();
        String briefText = String.join("\n", briefs);
        check("briefs 含七组全名", BuiltinGroups.GROUP_NAMES.stream()
                .allMatch(g -> briefText.contains("builtin:core:" + g)), briefText);
        check("briefs 不含详解（省 token 的分界）",
                !briefText.contains("ISO 周跨月") && !briefText.contains("不是抹掉"),
                briefText);
        String help = reg.help("todo.todo_mark", Map.of());
        check("help 给 usage + 参数", help != null && help.contains("todo_mark(id, status")
                && help.contains("参数:"), String.valueOf(help));
        check("ALWAYS_ON 禁用被拒", !reg.setEnabled("builtin:core:session", false)
                && reg.isEnabled("builtin:core:session"), "会话组被关掉了");

        // ── ② 执行面 ───────────────────────────────────────────────
        System.out.println("== 2 执行面（真的落到 Store） ==");
        SnowLeopard.CallerScope sc = core.forCaller("checker");
        BuiltinToolkit kit = sc.tools();

        Result add = kit.call("todo", "todo_add", Map.of("text", "写内置组"));
        check("todo_add → Ok 且带 id", ok(add) && data(add).contains("id"), String.valueOf(add));
        Result sub = kit.call("todo", "todo_add_sub",
                Map.of("parent_id", idOf(add), "text", "子项"));
        check("todo_add_sub → Ok 且 parentId 对上",
                ok(sub) && data(sub).contains(idOf(add)), String.valueOf(sub));
        Result mark = kit.call("todo", "todo_mark", Map.of("id", idOf(add), "status", "DONE"));
        check("todo_mark DONE → Ok", ok(mark) && data(mark).contains("DONE"),
                String.valueOf(mark));
        Result list = kit.call("todo", "todo_list", Map.of());
        check("todo_list 两条（父 + 子）", ok(list) && data(list).contains("写内置组")
                && data(list).contains("子项"), String.valueOf(list));
        Result badStatus = kit.call("todo", "todo_mark",
                Map.of("id", idOf(add), "status", "FINISHED"));
        check("非法状态 → Error（不是假成功）", badStatus instanceof Result.Error,
                String.valueOf(badStatus));

        // notes：创建 / 追加 / 读 / 搜
        check("note_create → Ok", ok(kit.call("notes", "note_create",
                Map.of("path", "d1/a.md", "content", "第一段"))), "");
        check("note_append → Ok", ok(kit.call("notes", "note_append",
                Map.of("path", "d1/a.md", "content", "\n第二段"))), "");
        Result read = kit.call("notes", "note_read", Map.of("path", "d1/a.md"));
        check("note_read 读回两段", ok(read) && data(read).contains("第一段")
                && data(read).contains("第二段"), String.valueOf(read));
        Result search = kit.call("notes", "note_search", Map.of("keyword", "第二段"));
        check("note_search 命中", ok(search) && data(search).contains("a.md"), String.valueOf(search));

        // memory：写一天再读（记忆工具必须真读盘）
        sc.memory().appendDay(java.time.LocalDate.of(2026, 10, 22), "22 日的段");
        Result mread = kit.call("memory", "memory_read",
                Map.of("level", "day", "period", "2026-10-22"));
        check("memory_read(day) 读回盘上内容",
                ok(mread) && data(mread).contains("22 日的段"), String.valueOf(mread));
        Result miss = kit.call("memory", "memory_read",
                Map.of("level", "day", "period", "1999-01-01"));
        check("空档 → Error 且说明稀疏是常态",
                miss instanceof Result.Error && ((Result.Error) miss).desc().contains("稀疏"),
                String.valueOf(miss));
        Result badLevel = kit.call("memory", "memory_read",
                Map.of("level", "decade", "period", "1990"));
        check("非法层级 → Error（带可选值）",
                badLevel instanceof Result.Error && ((Result.Error) badLevel).desc().contains("day|week"),
                String.valueOf(badLevel));
        Result repair = kit.call("memory", "memory_repair", Map.of());
        check("memory_repair 可用（非 Error）", !(repair instanceof Result.Error),
                String.valueOf(repair));

        // session
        Result usage = kit.call("session", "session_usage", Map.of());
        check("session_usage 带注入行", ok(usage) && data(usage).contains("会话用量"),
                String.valueOf(usage));
        check("session_yield → Ok", ok(kit.call("session", "session_yield", Map.of())), "");

        // ── ③ 两段式 ───────────────────────────────────────────────
        System.out.println("== 3 两段式（首段不落盘） ==");
        Result del1 = kit.call("notes", "note_delete", Map.of("path", "d1/a.md"));
        String token = tokenOf(del1);
        check("note_delete 首段给令牌且不删",
                ok(del1) && !token.isBlank()
                        && ok(kit.call("notes", "note_read", Map.of("path", "d1/a.md"))),
                String.valueOf(del1));
        Result del2 = kit.call("notes", "note_delete",
                Map.of("path", "d1/a.md", "confirm_token", token));
        check("带令牌 → 移入回收站", ok(del2) && data(del2).contains(".trash"),
                String.valueOf(del2));
        check("删后读不到", kit.call("notes", "note_read", Map.of("path", "d1/a.md"))
                instanceof Result.Error, "文件还在");

        Result c1 = kit.call("memory", "memory_correct",
                Map.of("action", "add", "target_ref", "day:2026-10-22", "note", "口径改这里"));
        String ctoken = tokenOf(c1);
        check("memory_correct 首段给令牌且未落盘",
                ok(c1) && !ctoken.isBlank() && sc.corrections().list().isEmpty(),
                String.valueOf(c1));
        Result c2 = kit.call("memory", "memory_correct", Map.of("action", "add",
                "target_ref", "day:2026-10-22", "note", "口径改这里", "confirm_token", ctoken));
        check("带令牌 → 纠错落盘", ok(c2) && sc.corrections().list().size() == 1,
                String.valueOf(c2));

        // ── ④ 失败形态 ─────────────────────────────────────────────
        System.out.println("== 4 失败形态（模型错 vs 宿主错） ==");
        Result unknown = kit.call("todo", "todo_explode", Map.of());
        check("未知工具 → Error 且提示查清单（提示里的工具名必须真实存在）",
                unknown instanceof Result.Error && ((Result.Error) unknown).desc().contains("tool_list")
                        && ((Result.Error) unknown).desc().contains("tool_help"),
                String.valueOf(unknown));
        Result missing = kit.call("todo", "todo_add", Map.of());
        check("参数缺失 → Error（不是抛异常）", missing instanceof Result.Error,
                String.valueOf(missing));
        boolean threw = false;
        try {
            kit.call("nope", "x", Map.of());
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("未知内置组");
        }
        check("未知组 → IllegalArgumentException（宿主装配错）", threw, "没抛或消息不对");

        // ── ⑤ 隔离（红线 3） ──────────────────────────────────────
        System.out.println("== 5 调用 ID 隔离 ==");
        SnowLeopard.CallerScope other = core.forCaller("other");
        Result otherList = other.tools().call("todo", "todo_list", Map.of());
        check("别的 ID 看不到本 ID 的待办", ok(otherList) && !data(otherList).contains("写内置组"),
                String.valueOf(otherList));
        check("别的 ID 读不到本 ID 的笔记",
                other.tools().call("notes", "note_read", Map.of("path", "d1/a.md"))
                        instanceof Result.Error, "跨 ID 读到了");

        // ── ⑥ 权限默认（文件模式） ─────────────────────────────────
        System.out.println("== 6 权限文件下的自带默认 ==");
        Path perm = tmp.resolve("perm.json");
        Files.writeString(perm, """
                {"groups": {"mod:ghost:g": {"enabled": true, "tools": "*"}}}
                """);
        SnowLeopard byFile = SnowLeopard.bootstrap(tmp.resolve("filemode"),
                SnowLeopard.Options.fromFiles(perm, null));
        check("文件只列外部组 → 内置六组仍默认开",
                BuiltinGroups.GROUP_NAMES.stream().allMatch(g ->
                        byFile.permissions().table().allows("builtin:core:" + g, "*")), "");
        Path perm2 = tmp.resolve("perm2.json");
        Files.writeString(perm2, """
                {"groups": {"builtin:core:todo": {"enabled": false, "tools": "*"}}}
                """);
        SnowLeopard off = SnowLeopard.bootstrap(tmp.resolve("filemode2"),
                SnowLeopard.Options.fromFiles(perm2, null));
        check("显式关 NORMAL 组 → 真的关（不被默认开回）",
                !off.permissions().table().allows("builtin:core:todo", "todo_add"),
                "又被开回来了");
        check("同文件下 ALWAYS_ON 组仍开",
                off.permissions().table().allows("builtin:core:memory", "memory_read"), "");

        // ── ⑦ 清单一致性 ───────────────────────────────────────────
        System.out.println("== 7 清单与执行面同源 ==");
        int declared = BuiltinGroups.all().stream()
                .mapToInt(p -> p.describe().tools().size()).sum();
        check("清单工具数 = 注册数 = 35", declared == 35 && declared == reg.toolCount(),
                "清单 " + declared + " / 注册 " + reg.toolCount());
        check("每组工具都有 usage + params（拒载规则自己先过）",
                reg.groups().stream().flatMap(g -> g.tools().stream())
                        .allMatch(t -> t.usage() != null && !t.usage().isBlank() && t.params() != null
                                && !t.params().isEmpty()), "");

        // ── ⑧ 工具目录与工作集（第 7 组，2026-10-27） ────────────────
        System.out.println("== 8 工具目录与工作集 ==");
        var me = core.forCaller("toolcheck");
        String block = reg.injectionBlock();
        check("注入块含分组用法说明", block.contains("【工具的分组方式】")
                && block.contains("tool_help") && block.contains("tool_scope"), block);
        check("注入块按来源分节", block.contains("[builtin:core] 7 组"), block);
        check("注入块**不含组内工具名**（组内工具信息不主动注入）",
                !block.contains("todo_mark") && !block.contains("group_send"), block);
        check("注入块每组带工具数", block.contains("builtin:core:todo — ") && block.contains("  (6)"), block);

        check("组详解含完整目录（全部成员名）", reg.groups().stream().allMatch(g ->
                        g.details() != null && g.tools().stream().allMatch(t -> g.details().contains(t.name()))),
                "有组详解缺成员");

        Result catList = me.tools().call("tool", "tool_list", Map.of());
        check("tool_list 列组并给下一步用法", ok(catList) && data(catList).contains("builtin:core:tool")
                && data(catList).contains("tool_help"), data(catList));
        Result listBuiltin = me.tools().call("tool", "tool_list", Map.of("source", "builtin"));
        check("tool_list 来源过滤生效", ok(listBuiltin) && !data(listBuiltin).contains("[mod:"), data(listBuiltin));

        check("未取用时工作集为空", !core.exposure().isActive("toolcheck", "builtin:core:todo"), "");
        Result catHelp = me.tools().call("tool", "tool_help", Map.of("targets", "todo"));
        check("tool_help 组级给成员名单", ok(catHelp) && data(catHelp).contains("todo_mark")
                && data(catHelp).contains("成员 6 个"), data(catHelp));
        check("tool_help 组级**不含参数**（第三跳留给 组.工具）", ok(catHelp) && !data(catHelp).contains("required"),
                data(catHelp));
        check("tool_help 后该组进入工作集", core.exposure().isActive("toolcheck", "builtin:core:todo"), "");

        Result helpTool = me.tools().call("tool", "tool_help", Map.of("targets", "todo.todo_mark"));
        check("tool_help 工具级给用法 + 参数", ok(helpTool) && data(helpTool).contains("用法:")
                && data(helpTool).contains("参数:"), data(helpTool));
        check("组内筛选：只取了 todo_mark，未取的组仍是空工作集",
                !core.exposure().isActive("toolcheck", "builtin:core:notes"), "");

        Result tooMany = me.tools().call("tool", "tool_help",
                Map.of("targets", "todo,notes,session,memory,group"));
        check("tool_help 超过 4 个 → Error", tooMany instanceof Result.Error, String.valueOf(tooMany));

        Result scopeAdd = me.tools().call("tool", "tool_scope", Map.of("action", "add", "target", "notes"));
        check("tool_scope add 生效", ok(scopeAdd) && core.exposure().isActive("toolcheck", "builtin:core:notes"),
                data(scopeAdd));
        Result scopeRm = me.tools().call("tool", "tool_scope", Map.of("action", "remove", "target", "notes"));
        check("tool_scope remove 生效", ok(scopeRm) && !core.exposure().isActive("toolcheck", "builtin:core:notes"),
                data(scopeRm));

        check("目录组恒在（元工具不可裁）",
                core.exposure().isActive("toolcheck", ToolExposure.CATALOG_GROUP), "");
        check("工作集不影响权限（isEnabled 不变）", reg.isEnabled("builtin:core:todo"), "");
        check("无组提示每工具每会话只给一次", core.exposure().markSoloNotified("toolcheck", "x.y")
                && !core.exposure().markSoloNotified("toolcheck", "x.y"), "");

        // 保留名与详解 WARN（直接对注册表做，不经 bootstrap——bootstrap 里的清单是合法的）
        ToolRegistry scratch = new ToolRegistry();
        boolean soloTaken = scratch.register(new ToolRegistry.Group(
                new snowleopard.core.names.Names.Qualified(
                        snowleopard.core.names.Names.SourceType.MOD, "x", "solo"),
                "c", "s", "d", Availability.NORMAL,
                List.of(new ToolRegistry.Tool("t", "s", "u", Map.of("type", "object")))), false);
        check("「solo」是保留组名（真组撞名拒载）", !soloTaken, "真组占了 solo");
        boolean noDetails = scratch.register(new ToolRegistry.Group(
                new snowleopard.core.names.Names.Qualified(
                        snowleopard.core.names.Names.SourceType.MOD, "y", "g2"),
                "c", "s", null, Availability.NORMAL,
                List.of(new ToolRegistry.Tool("t2", "s", "u", Map.of("type", "object")))), false);
        check("详解缺失只 WARN 不拒载", noDetails && scratch.issues().stream().anyMatch(i ->
                        i.level() == snowleopard.core.report.Issue.Level.WARN && i.message().contains("详解缺失")),
                String.valueOf(scratch.issues()));

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " builtin: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────

    private static Availability avail(ToolRegistry reg, String full) {
        for (ToolRegistry.Group g : reg.groups()) {
            if (g.full().equals(full)) {
                return g.availability();
            }
        }
        return null;
    }

    private static boolean ok(Result r) {
        return r instanceof Result.Ok;
    }

    private static String data(Result r) {
        return switch (r) {
            case Result.Ok o -> String.valueOf(o.data());
            case Result.Warn w -> String.valueOf(w.data());
            case Result.Severe s -> String.valueOf(s.data());
            case Result.Error e -> e.desc();
        };
    }

    /**
     * 从结果里取条目 id：{@code todo_add} 的 data 是 {@code TodoStore.Item} 记录
     * （{@code toString} 形态 {@code Item[id=t1, …]}），不是 Map——按字段名抠，
     * 不猜具体承载类型（换承载不改测试）。
     */
    private static String idOf(Result r) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\bid=([A-Za-z0-9_-]+)").matcher(data(r));
        return m.find() ? m.group(1) : "";
    }

    private static String tokenOf(Result r) {
        Object v = mapOf(r).get("confirm_token");
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Result r) {
        if (r instanceof Result.Ok o && o.data() instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
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

    private BuiltinCheck() {
    }
}
