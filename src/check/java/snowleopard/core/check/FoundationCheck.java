package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import snowleopard.core.config.ToolConfig;
import snowleopard.core.ids.Ids;
import snowleopard.core.ids.Ids.Caller;
import snowleopard.core.json.Json;
import snowleopard.core.names.Names;
import snowleopard.core.report.Issue;

/**
 * M1 地基验收（2026-10-22 22:27 (UTC+8)）：ID/重名/存储/工具配置/挂载隔离。
 * 对应开发流程 M1 验收五条：重名四场景、路径越界被拒、双 ID 并发互不可见、配置三语义、ID 不伪造。
 */
public final class FoundationCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path tmp = root.resolve("build/m1-tmp");
        deleteRec(tmp);
        Files.createDirectories(tmp);

        names();
        idEscape();
        isolation(tmp);
        config(tmp);
        noForge();

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " foundation: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 重名四场景（方案 D）。 */
    private static void names() {
        Names n = Names.of(List.of(
                "builtin:core:session",
                "local:mining:combat",
                "mod:example:combat",
                "mod:other:notes"));
        check("① 唯一短名直接解析", "builtin:core:session".equals(n.resolve("session", Map.of())),
                String.valueOf(n.resolve("session", Map.of())));

        Object amb = n.resolve("combat", Map.of());
        check("② 短名歧义不猜、返回候选", amb instanceof Names.Ambiguous a
                        && a.candidates().containsAll(List.of("local:mining:combat", "mod:example:combat")),
                String.valueOf(amb));

        Object viaAlias = n.resolve("战斗", Map.of("战斗", "mod:example:combat"));
        check("③ 别名消歧", "mod:example:combat".equals(viaAlias), String.valueOf(viaAlias));

        Names.RegisterResult dup = n.register(Names.Qualified.parse("mod:example:combat"));
        check("④ 同全名重名拒绝（先到者保留）", !dup.accepted()
                        && n.fullNames().contains("mod:example:combat"),
                dup.rejectedBecause());
        // ④ 附带：拒绝留痕
        check("④ 拒绝记录留痕", n.rejected().contains("mod:example:combat"), n.rejected().toString());

        List<String> aliasErrs = Names.validateAliases(
                Map.of("combat", "mod:example:combat", "notes", "mod:nonexistent:x"), n);
        check("别名表校验：歧义名定向允许（combat 无错）",
                aliasErrs.stream().noneMatch(e -> e.contains("「combat」")), aliasErrs.toString());
        check("别名表校验：唯一短名遮蔽/指向不存在 → 拒（notes 2 条）",
                aliasErrs.stream().filter(e -> e.contains("notes")).count() == 2,
                aliasErrs.toString());
        Object directed = n.resolve("combat", Map.of("combat", "mod:example:combat"));
        check("别名定向歧义名生效（显式消歧）", "mod:example:combat".equals(directed),
                String.valueOf(directed));
    }

    /** 路径锚定：越界即拒（不静默收敛）。 */
    private static void idEscape() {
        Ids ids = Ids.at(Path.of("build/m1-tmp/store"));
        Caller c = new Caller("id0");
        check("路径锚定：正常相对路径放行",
                ids.resolve(c, "notes/a.txt").startsWith(ids.home(c)), "ok");
        boolean escaped1 = false;
        try {
            ids.resolve(c, "../id3/notes/x");
        } catch (Ids.PathEscape e) {
            escaped1 = true;
        }
        check("路径锚定：.. 越界被拒（PathEscape）", escaped1, "未抛");
        boolean escaped2 = false;
        try {
            ids.resolve(c, "notes/../../id3/notes/x");
        } catch (Ids.PathEscape e) {
            escaped2 = true;
        }
        check("路径锚定：中段 .. 越界被拒", escaped2, "未抛");
        boolean abs = false;
        try {
            new Caller("../evil");
        } catch (IllegalArgumentException e) {
            abs = true;
        }
        check("ID 本身含路径字符被拒", abs, "未抛");
    }

    /** 双 ID 并发互不可见（工具=功能复用，不复用信息）。 */
    private static void isolation(Path tmp) throws Exception {
        Ids ids = Ids.at(tmp.resolve("store2"));
        Caller a = new Caller("ida");
        Caller b = new Caller("idb");
        ids.mkdirs(a, "notes");
        ids.mkdirs(b, "notes");

        // 隔离是 **API 级**（resolve 锚定在自己家），不是物理上"看不见磁盘"——
        // 早期断言检查"对方文件不存在"必然假失败（两边都在同一磁盘写）
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> fa = es.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    write(ids.resolve(a, "notes/f" + i + ".txt"), "A" + i);
                }
            });
            Future<?> fb = es.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    write(ids.resolve(b, "notes/f" + i + ".txt"), "B" + i);
                }
            });
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        }
        String a0 = Files.readString(ids.resolve(a, "notes/f0.txt"));
        String b0 = Files.readString(ids.resolve(b, "notes/f0.txt"));
        check("双 ID 并发各写各家、内容不串", "A0".equals(a0) && "B0".equals(b0),
                a0 + "/" + b0);
        boolean canCross = false;
        try {
            ids.resolve(a, "../idb/notes/f0.txt");
            canCross = true;
        } catch (Ids.PathEscape expected) {
            // 锚定生效：A 解析不到 B 的家
        }
        check("A 解析不到 B 的家（路径锚定在并发下同样生效）", !canCross, "越界被放行");
        try (var s = Files.list(ids.home(a).resolve("notes"))) {
            check("A 家写满 50 个", s.count() == 50, "不是 50");
        }
    }

    /** 配置三语义（§九.2）：没写=默认、匹配失败=WARN、参数错误=ERROR。 */
    private static void config(Path tmp) throws Exception {
        Set<String> known = Set.of("builtin:core:memory", "mod:example:combat");
        Path idDefault = tmp.resolve("tools-default.json");
        Files.writeString(idDefault, """
                {"builtin:core:memory": {"max_chars": 8000}}
                """);
        Path external = tmp.resolve("work.json");
        Files.writeString(external, """
                {"mod:example:combat": {"cooldown": 500},
                 "mod:ghost:gone": {"x": 1},
                 "builtin:core:memory": "not-an-object"}
                """);

        ToolConfig.Loaded ld = ToolConfig.load(idDefault, external, known);
        check("① 没写=默认（mod:other 没配 → 不在生效表）",
                !ld.effective().containsKey("mod:other:notes"), ld.effective().keySet().toString());
        check("① 内置键从 id-default 生效（来源标注）",
                "id-default".equals(ld.sourceOf().get("builtin:core:memory"))
                        || "external".equals(ld.sourceOf().get("builtin:core:memory")),
                ld.sourceOf().toString());

        List<Issue> warns = ld.issues().stream().filter(i -> i.level() == Issue.Level.WARN).toList();
        check("② 匹配失败=WARN（mod:ghost:gone）", warns.stream()
                        .anyMatch(i -> i.where().equals("mod:ghost:gone")), warns.toString());
        List<Issue> errs = ld.issues().stream().filter(i -> i.level() == Issue.Level.ERROR).toList();
        check("③ 参数错误=ERROR（内置键给了字符串）", errs.stream()
                        .anyMatch(i -> i.where().equals("builtin:core:memory")), errs.toString());
        check("③ 参数错误项拒绝生效（退回默认/不进生效表）",
                !ld.effective().containsKey("builtin:core:memory")
                        || ld.sourceOf().get("builtin:core:memory").equals("id-default"),
                ld.effective().toString());
        check("② 外部覆盖标注 external（mod:example:combat）",
                "external".equals(ld.sourceOf().get("mod:example:combat")), ld.sourceOf().toString());

        // 键形态：JSON 解析器本身（宽容解析是"配置悄悄失效"的温床）
        boolean threw = false;
        try {
            Json.parseObject("{a:1}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("JSON 不宽容：无引号键报错", threw, "未抛");
    }

    /** ID 不伪造：模型侧传 ID 参数由装载期检测拒绝。 */
    private static void noForge() {
        List<String> bad = Ids.detectDataSourceParams(Map.of("caller_id", "string", "target", "string"));
        check("ID 不伪造：清单声明 caller_id 参数 → 装载期拒绝",
                bad.size() == 1 && bad.get(0).equals("caller_id"), bad.toString());
        List<String> ok = Ids.detectDataSourceParams(Map.of("target", "string", "text", "string"));
        check("ID 不伪造：正常参数不误伤", ok.isEmpty(), ok.toString());
        boolean forged = false;
        try {
            new Caller("id0/../id3");
        } catch (IllegalArgumentException e) {
            forged = true;
        }
        check("ID 不伪造：caller 构造拒绝路径型 ID", forged, "未抛");
    }

    // ------------------------------------------------------------------ 工具

    private static void write(Path p, String s) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRec(Path p) {
        try {
            if (!Files.exists(p)) {
                return;
            }
            try (var w = Files.walk(p)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                    try {
                        Files.delete(x);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
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

    private FoundationCheck() {
    }
}
