package snowleopard.core.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 架构检查（2026-10-24 06:10 (UTC+8)）：**包级依赖不能有环**。
 *
 * <p>为什么单列一个检查器：环是"改一处能过、合起来必坏"的那类结构问题——它不报错、
 * 不失败，只是让"改 A 要问 B、改 B 又要问 A"，最后谁都改不动。靠人看不出来，
 * 所以必须由构建来判（完整性靠构建方式保证，不靠人记清单——同一条纪律）。
 *
 * <p>判据（宁窄勿宽，宽规则曾一次 87 误判）：
 * <ol>
 *   <li>只算 {@code snowleopard.core.*} 之间的边（JDK / 外部依赖不算）；</li>
 *   <li>**父子包之间的边不计**：{@code core → core.memory} 与 {@code core.memory → core} 是
 *       门面与子包的天然双向，把它算成环会淹掉真问题；真正要抓的是**互不为祖先**的两包之间的环；</li>
 *   <li>环 = 有向图上的闭环（DFS 回溯边），长度 ≥2。</li>
 * </ol>
 *
 * <p>本轮抓到的一对：{@code registry ↔ report}——registry 登记问题时用 {@code report.Issue}，
 * 而 {@code report.StartupReport} 要收 {@code ToolRegistry} 才能渲染。拆法二选一：
 * 把 {@code Issue} 下沉到更低的包（如 {@code snowleopard.core.report} 之外），
 * 或让 {@code StartupReport} 只接一份数据快照（"渲染不要认识活对象"）。
 */
public final class ArchCheck {

    private static final Pattern PKG = Pattern.compile("^package\\s+([a-z][\\w.]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMP = Pattern.compile("^import\\s+(snowleopard\\.core[\\w.]*)\\s*;",
            Pattern.MULTILINE);

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path src = root.resolve("src/main/java");

        System.out.println("== 1 包依赖图 ==");
        Map<String, Set<String>> graph = new TreeMap<>();
        int files = 0;
        try (var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Matcher pm = PKG.matcher(text);
                if (!pm.find()) {
                    continue;
                }
                files++;
                String from = pm.group(1);
                graph.computeIfAbsent(from, k -> new TreeSet<>());
                Matcher im = IMP.matcher(text);
                while (im.find()) {
                    String raw = im.group(1);
                    // import 可能指向类（截到包）或包
                    String to = raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : pkgOfClass(root, raw);
                    if (to == null || to.equals(from)) {
                        continue;
                    }
                    if (isAncestor(from, to) || isAncestor(to, from)) {
                        continue;   // 父子边：门面与子包的天然双向，不算环
                    }
                    graph.get(from).add(to);
                    graph.computeIfAbsent(to, k -> new TreeSet<>());
                }
            }
        } catch (IOException e) {
            check("源码树可读", false, e.getMessage());
        }
        int edges = graph.values().stream().mapToInt(Set::size).sum();
        check("扫到源文件 " + files + " 个", files > 0, String.valueOf(files));
        check("跨包边 " + edges + " 条（父子边已排除）", edges > 0, String.valueOf(edges));

        System.out.println("== 2 环检测 ==");
        List<List<String>> cycles = findCycles(graph);
        for (List<String> c : cycles) {
            check("环：" + String.join(" → ", c), false, "");
        }
        check("包级依赖 0 环（互不为祖先的包之间）", cycles.isEmpty(),
                cycles.isEmpty() ? "" : cycles.size() + " 个环");

        System.out.println("PASS arch: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** import 的类 → 它所在的包（在源码树里找；找不到返回 null，宁漏不算边）。 */
    private static String pkgOfClass(Path root, String fqcn) {
        String rel = fqcn.replace('.', '/') + ".java";
        Path candidate = root.resolve("src/main/java").resolve(rel);
        if (Files.isRegularFile(candidate)) {
            int dot = fqcn.lastIndexOf('.');
            return dot < 0 ? "" : fqcn.substring(0, dot);
        }
        // 可能是嵌套类/子包：按最长包名前缀找目录
        List<String> parts = new ArrayList<>(List.of(fqcn.split("\\.")));
        for (int i = parts.size() - 1; i >= 1; i--) {
            String pkg = String.join(".", parts.subList(0, i));
            String cls = String.join(".", parts.subList(i, parts.size()));
            Path dir = root.resolve("src/main/java").resolve(pkg.replace('.', '/'));
            if (Files.isRegularFile(dir.resolve(cls + ".java"))) {
                return pkg;
            }
        }
        return null;
    }

    private static boolean isAncestor(String a, String b) {
        return b.startsWith(a + ".");
    }

    /** 回溯边找环（每个环以最小节点起算，去重）。 */
    private static List<List<String>> findCycles(Map<String, Set<String>> graph) {
        Map<String, String> color = new LinkedHashMap<>();   // 灰=在栈上
        List<List<String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String start : graph.keySet()) {
            dfs(stackOf(start), graph, color, out, seen);
        }
        return out;
    }

    private static void dfs(java.util.Deque<String> stack, Map<String, Set<String>> graph,
                              Map<String, String> color, List<List<String>> out, Set<String> seen) {
        String node = stack.peekLast();
        color.put(node, "GREY");
        for (String next : graph.getOrDefault(node, Set.of())) {
            if ("GREY".equals(color.get(next))) {
                // 回溯边：从 next 到栈顶就是环
                List<String> cycle = new ArrayList<>(stack);
                int at = cycle.indexOf(next);
                if (at >= 0) {
                    List<String> ring = new ArrayList<>(cycle.subList(at, cycle.size()));
                    ring.add(next);
                    String key = String.join("→", rotateMin(ring));
                    if (seen.add(key)) {
                        out.add(ring);
                    }
                }
            } else if (!"BLACK".equals(color.get(next))) {
                stack.addLast(next);
                dfs(stack, graph, color, out, seen);
                stack.removeLast();
            }
        }
        color.put(node, "BLACK");
    }

    private static java.util.Deque<String> stackOf(String first) {
        java.util.Deque<String> d = new java.util.ArrayDeque<>();
        d.addLast(first);
        return d;
    }

    /** 环的最小表示（把字典序最小的节点转到开头），同一个环只算一次。 */
    private static List<String> rotateMin(List<String> ring) {
        int min = 0;
        for (int i = 1; i < ring.size(); i++) {
            if (ring.get(i).compareTo(ring.get(min)) < 0) {
                min = i;
            }
        }
        List<String> r = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            r.add(ring.get((min + i) % ring.size()));
        }
        return r;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "（" + detail + "）"));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "（" + detail + "）"));
        }
    }
}
