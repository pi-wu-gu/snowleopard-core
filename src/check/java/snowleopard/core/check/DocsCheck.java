package snowleopard.core.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        String rm = read(readme);
        String an = read(analysis);
        String dv = read(devflow);
        String cm = read(concept);
        String ag = read(guide);

        System.out.println("== 1 README 的 AI 声明与里程碑表 ==");
        check("README 存在", rm != null, String.valueOf(readme));
        if (rm != null) {
            check("「几乎完全由 AI 完成」声明在（用户硬要求）",
                    rm.contains("几乎完全由 AI 完成"), "");
            for (String m : List.of("M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7")) {
                check("里程碑表含 " + m, rm.contains("| " + m) || rm.contains(m + " "), "");
            }
        }

        System.out.println("== 2 设计文档存在且带时间戳 ==");
        check("可行性分析存在", an != null, String.valueOf(analysis));
        check("开发流程存在", dv != null, String.valueOf(devflow));
        check("分析带时间戳记录（UTC+8）", an != null && an.contains("(UTC+8)"), "");
        check("开发流程带时间戳记录（UTC+8）", dv != null && dv.contains("(UTC+8)"), "");

        System.out.println("== 3 README/概念地图/接入指南 的类名必须真实存在 ==");
        List<String> missing = new ArrayList<>();
        for (String doc : new String[]{rm, cm, ag}) {
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
        for (String doc : new String[]{rm, an, dv, cm, ag}) {
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

        System.out.println("PASS docs: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 类名是否存在于 src/main/java（按文件名找）。 */
    private static boolean classExists(Path root, String cls) {
        Path src = root.resolve("src/main/java");
        try (var walk = Files.walk(src)) {
            return walk.anyMatch(p -> p.getFileName().toString().equals(cls + ".java"));
        } catch (IOException e) {
            return false;
        }
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
