package snowleopard.core.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import snowleopard.core.builtin.BuiltinGroups;

/**
 * M7 验收（2026-10-22 23:58 (UTC+8)）：文档检查（星期五 B8 的同思路，范围按本仓裁剪）。
 *
 * <p>判据（都是"文档漂移"的确切形态，不做宽泛扫）：
 * <ol>
 *   <li>README 的"几乎完全由 AI 完成"声明在（用户硬要求——丢了就是漂移）；</li>
 *   <li>README 里程碑表含 M0–M7 全行；</li>
 *   <li>两份设计文档存在且带时间戳记录行（用户全局偏好：记录一律带时间戳）；</li>
 *   <li>README 反引号里的 {@code XxxYyy} 类名必须真实存在于 src（防文档教已删的类）；</li>
 *   <li>跨文档一致性：开发流程提到的机制键（confirm_token / session_yield / memory_repair / purge）
 *       分析文档也必须有（漂移的常见形态是"一处改了另一处没跟"）；</li>
 *   <li>废弃符号不得出现在使用类文档（清单现为空；退役符号时往里加）。</li>
 *   <li>概念地图纳管（2026-10-23 01:20 (UTC+8) 波次 1）：存在 + 带时间戳 + 反引号类名真实
 *       + 四机制键齐全——新增使用类文档一律纳管，防漏检（星期五 B8 白名单的镜像教训）。</li>
 * </ol>
 */
public final class DocsCheck {

    /** 已退役、文档不许再教的符号（现阶段为空；退役一个登记一个）。 */
    private static final List<String> STALE = List.of(
            "MemoryStore.read() 返回 Result",   // 已改名 readRouted——文档若教旧签名即漂移
            "Names.resolve 返回 Object"          // 波次 4（2026-10-23）已改 sealed 三态 Names.Resolution
    );

    /** 开发流程与分析两文都必须出现的机制键（跨文档一致性）。 */
    private static final List<String> SHARED_KEYS = List.of(
            "confirm_token", "session_yield", "memory_repair", "purge");

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path readme = root.resolve("README.md");
        Path analysis = root.resolve("docs/工具组支持可行性分析-2026-10-22.md");
        Path devflow = root.resolve("docs/开发流程-2026-10-22.md");
        Path concept = root.resolve("docs/概念地图-2026-10-23.md");
        Path guide = root.resolve("docs/接入指南-2026-10-23.md");
        Path index = root.resolve("docs/INDEX.md");
        Path spec = root.resolve("docs/行为规格清单-2026-10-23.md");
        Path builtin = root.resolve("docs/内置工具组-2026-10-23.md");

        String rm = read(readme);
        String an = read(analysis);
        String dv = read(devflow);
        String cm = read(concept);
        String ag = read(guide);
        String idx = read(index);
        String specTxt = read(spec);
        String bt = read(builtin);

        System.out.println("== 1 README 的 AI 声明（里程碑表已迁开发流程 §九） ==");
        check("README 存在", rm != null, String.valueOf(readme));
        if (rm != null) {
            check("「几乎完全由 AI 完成」声明在（用户硬要求）",
                    rm.contains("几乎完全由 AI 完成"), "");
        }
        // 里程碑表 2026-10-23 归档进开发流程（README 瘦身=快速开始；判据与文档同轮迁移）
        check("里程碑状态表在开发流程", dv != null && dv.contains("里程碑状态"), "");
        for (String m : List.of("M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7")) {
            check("里程碑表含 " + m, dv != null && (dv.contains("| " + m) || dv.contains(m + " ")), "");
        }

        System.out.println("== 2 设计文档存在且带时间戳 ==");
        check("可行性分析存在", an != null, String.valueOf(analysis));
        check("开发流程存在", dv != null, String.valueOf(devflow));
        check("内置工具组文档存在（使用类，新增工具必须同步）", bt != null, String.valueOf(builtin));
        check("内置工具组文档带时间戳记录（UTC+8）", bt != null && bt.contains("(UTC+8)"), "");
        check("分析带时间戳记录（UTC+8）", an != null && an.contains("(UTC+8)"), "");
        check("开发流程带时间戳记录（UTC+8）", dv != null && dv.contains("(UTC+8)"), "");

        System.out.println("== 3 使用类文档的类名必须真实存在 ==");
        List<String> missing = new ArrayList<>();
        for (String doc : new String[]{rm, cm, ag, idx, specTxt, bt}) {
            if (doc == null) {
                continue;
            }
            Matcher mt = Pattern.compile("`([A-Z][A-Za-z0-9]{2,})`").matcher(doc);
            while (mt.find()) {
                String cls = mt.group(1);
                if (!classExists(root, cls)) {
                    missing.add(cls);
                }
            }
        }
        check("反引号类名都存在（0 个悬空）", missing.isEmpty(), missing.toString());

        System.out.println("== 4 跨文档机制键一致 ==");
        for (String key : SHARED_KEYS) {
            boolean inDv = dv != null && dv.contains(key);
            boolean inAn = an != null && an.contains(key);
            check("机制键 " + key + " 两文都有", inDv && inAn,
                    "devflow=" + inDv + " analysis=" + inAn);
        }

        System.out.println("== 5 使用类文档不提废弃符号 ==");
        List<String> staleHits = new ArrayList<>();
        for (String doc : new String[]{rm, an, dv, cm, ag, idx, specTxt, bt}) {
            if (doc == null) {
                continue;
            }
            for (String s : STALE) {
                // 拆成可搜索的形态：只查"旧签名"这类明确文本
                String needle = s.contains("返回 Result") ? "MemoryStore.read() 返回 Result" : s;
                if (doc.contains(needle)) {
                    staleHits.add(needle);
                }
            }
        }
        check("废弃符号 0 命中", staleHits.isEmpty(), staleHits.toString());

        System.out.println("== 6 概念地图纳管（波次 1，2026-10-23 01:20） ==");
        check("概念地图存在", cm != null, String.valueOf(concept));
        check("概念地图带时间戳记录（UTC+8）", cm != null && cm.contains("(UTC+8)"), "");
        for (String key : SHARED_KEYS) {
            check("概念地图收录机制键 " + key, cm != null && cm.contains(key), "");
        }
        boolean hasRedline = cm != null && cm.contains("接入红线");
        check("概念地图含接入红线章节", hasRedline, "");

        System.out.println("== 7 接入指南纳管（波次 2，2026-10-23 01:35） ==");
        check("接入指南存在", ag != null, String.valueOf(guide));
        check("接入指南带时间戳记录（UTC+8）", ag != null && ag.contains("(UTC+8)"), "");
        check("接入指南引用红线而不复制（单一事实源在概念地图）",
                ag != null && ag.contains("接入红线"), "");
        check("接入指南指向可运行示例（examples 即规格）",
                ag != null && ag.contains("examples/hello-provider"), "");

        System.out.println("== 8 文档地图纳管（波次 C，2026-10-23 02:30） ==");
        check("INDEX 存在", idx != null, String.valueOf(index));
        check("INDEX 带时间戳记录（UTC+8）", idx != null && idx.contains("(UTC+8)"), "");
        check("INDEX 三栏分轨（上手/参考/史料）", idx != null
                && idx.contains("上手") && idx.contains("参考") && idx.contains("史料"), "");
        check("INDEX 点名核心使用文档", idx != null
                && idx.contains("概念地图") && idx.contains("接入指南") && idx.contains("行为规格清单"), "");

        System.out.println("== 9 行为规格清单纳管（波次 E，2026-10-23 02:30） ==");
        check("行为规格清单存在", specTxt != null, String.valueOf(spec));
        check("规格清单带时间戳记录（UTC+8）", specTxt != null && specTxt.contains("(UTC+8)"), "");
        List<String> noNamed = new ArrayList<>();
        for (String cls : List.of("BuiltinCheck", "CommunityCheck", "SmokeCheck", "FoundationCheck",
                "RegistryCheck", "SafetyCheck", "WorkCheck", "MemoryCheck", "ShellCheck",
                "ReportCheck", "DocsCheck", "ExamplesCheck", "ArchCheck")) {
            if (specTxt == null || !specTxt.contains(cls)) {
                noNamed.add(cls);
            }
        }
        check("规格清单点名全部 13 个 Check 类（0 漏）", noNamed.isEmpty(), noNamed.toString());

        System.out.println("== 10 数字一致性（2026-10-24 06:20 (UTC+8)） ==");
        // 文档里的**数字**此前完全没人管：INDEX 写 298、本节标题写 19、内置工具数写 18，
        // 而代码早已是 358 / 20 / 32——存在性/时间戳/类名都查过了，唯独数字一路漂。
        // 判据只做"可静态判定的一致性"，不去猜运行期条数（那由各 Check 自己打印）。
        int specTotal = firstInt(specTxt, "运行总数 (\\d+)/(\\d+)");
        Map<String, Integer> perCheck = pairs(specTxt, "([a-z]+) (\\d+)");
        int perSum = perCheck.values().stream().mapToInt(Integer::intValue).sum();
        int sectionSum = 0;
        Matcher sm = Pattern.compile("## (\\w+?)Check（(\\d+)）").matcher(specTxt == null ? "" : specTxt);
        while (sm.find()) {
            sectionSum += Integer.parseInt(sm.group(2));
        }
        check("规格清单：总数 == 各检查器条数之和", specTotal > 0 && specTotal == perSum,
                specTotal + " vs " + perSum);
        check("规格清单：总数 == 各节标题条数之和", specTotal > 0 && specTotal == sectionSum,
                specTotal + " vs " + sectionSum);
        check("规格清单与 INDEX 的条数一致（跨文档同一个数字）",
                specTotal > 0 && specTotal == firstInt(idx, "(\\d+) 条断言"),
                specTotal + " vs " + firstInt(idx, "(\\d+) 条断言"));
        // 工具数是**能对着运行时判**的那一条：文档写的 32 必须等于注册表里真的 32
        int declaredTools = BuiltinGroups.all().stream()
                .mapToInt(p -> p.describe().tools().size()).sum();
        check("内置工具数：文档口径 == 注册实际 == 35",
                declaredTools == 35 && bt != null && bt.contains("35 工具"),
                "注册 " + declaredTools);

        System.out.println("== 10 内置工具组文档纳管（2026-10-23 19:40） ==");
        List<String> noGroup = new ArrayList<>();
        for (String g : List.of("builtin:core:tool", "builtin:core:session", "builtin:core:memory",
                "builtin:core:todo", "builtin:core:notes",
                "builtin:core:wake", "builtin:core:group")) {
            if (bt == null || !bt.contains(g)) {
                noGroup.add(g);
            }
        }
        check("内置文档点名七组全名（0 漏）", noGroup.isEmpty(), noGroup.toString());
        check("内置文档写清 35 工具口径（清单即政策）", bt != null && bt.contains("35 工具"), "");
        check("内置文档明确记账\"壳侧未接\"（未做的不许当做过）",
                bt != null && bt.contains("七组全部已接壳"), "");
        check("内置文档引用分析规范（单一事实源在分析文档）",
                bt != null && bt.contains("分析 §三"), "");

        // ── 工具清单（2026-10-27）：**新增/改名工具不改表 = 直接红** ──
        // 表按"组 / 暴露名 / 作用 / 实现路径"四列组织；这里只做能机器判的部分：
        // 口径数字、每个工具都在表里、实现路径列没被删。
        Path inv = root.resolve("docs/工具清单-2026-10-27.md");
        String invTxt = Files.isRegularFile(inv)
                ? Files.readString(inv, StandardCharsets.UTF_8) : null;
        int invTools = BuiltinGroups.all().stream()
                .mapToInt(p -> p.describe().tools().size()).sum();
        check("工具清单口径 == 运行时（7 组 35 工具）",
                invTxt != null && invTxt.contains("7 组 " + invTools + " 工具"),
                invTxt == null ? "文件不存在" : "缺 7 组 " + invTools + " 工具");
        List<String> notInTable = new ArrayList<>();
        if (invTxt != null) {
            for (snowleopard.core.spi.ToolGroupProvider p : BuiltinGroups.all()) {
                for (snowleopard.core.registry.ToolRegistry.Tool t : p.describe().tools()) {
                    if (!invTxt.contains(t.name())) {
                        notInTable.add(t.name());
                    }
                }
            }
        }
        check("工具清单列全每个工具（新增工具必须同步改表）",
                invTxt != null && notInTable.isEmpty(), notInTable.toString());
        check("工具清单带实现路径列（不是只有名字）",
                invTxt != null && invTxt.contains("BuiltinToolkit.java#")
                        && invTxt.contains("BuiltinGroups.java#"), "");

        System.out.println("PASS docs: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 取文档里第一个匹配的第 1 组数字（没有 → -1；-1 参与比较会直接判失败，不静默当 0）。 */
    private static int firstInt(String doc, String regex) {
        if (doc == null) {
            return -1;
        }
        Matcher m = Pattern.compile(regex).matcher(doc);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 取文档**头部**（第一个 `## ` 之前）里的 "名字 数字" 对。
     *
     * <p>只扫头部：正文里的"tick 12""10 秒"这类数量混进来会让条数变成随机数
     * （宽规则害人——星期五 B8 那次 87 误判就是这么来的）。
     */
    private static Map<String, Integer> pairs(String doc, String regex) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (doc == null) {
            return out;
        }
        int end = doc.indexOf("\n## ");
        String head = end < 0 ? doc : doc.substring(0, end);
        Matcher m = Pattern.compile(regex).matcher(head);
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 类名是否存在于 src/main/java 或 src/check/java（按文件名找；检查器也是真类）。 */
    private static boolean classExists(Path root, String cls) {
        for (String tree : new String[]{"src/main/java", "src/check/java"}) {
            Path src = root.resolve(tree);
            try (var walk = Files.walk(src)) {
                if (walk.anyMatch(p -> p.getFileName().toString().equals(cls + ".java"))) {
                    return true;
                }
            } catch (IOException e) {
                // 树不存在则跳过
            }
        }
        return false;
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

    private DocsCheck() {
    }
}
