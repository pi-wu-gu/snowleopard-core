package snowleopard.core.perm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import snowleopard.core.json.Json;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.report.Issue;

/**
 * 权限：能不能用（M3，2026-10-22 22:46 (UTC+8)，分析 §二.五）。
 *
 * <p>职责与工具配置**不合并**（分析 §10.6-10）：开关归这里，参数归 {@code ToolConfig}。
 * <ul>
 *   <li>默认启动 = 全权限（ID0）；指定 ID 启动按权限文件（输入文件优先于自带）；</li>
 *   <li>未启用工具<b>仅可见名称</b>，不加载简介/详解（禁用形同虚设的反面）；</li>
 *   <li>{@code ALWAYS_ON} 被写禁用 → 配置失败记 ERROR（配置不许把核心能力关掉）；</li>
 *   <li>热更新只影响<b>之后</b>的调用：在执行中的调用不回溯（半路撤权会产出"执行了一半"的状态）。</li>
 * </ul>
 */
public final class Permissions {

    /** 一份生效的权限表（快照：热更新换新表，旧表继续服务在执行的调用）。 */
    public record Table(Map<String, Set<String>> enabledTools,
                        List<Issue> issues,
                        String source,
                        boolean allOpen) {

        /** 兼容旧三参形态（=非全开）。 */
        public Table(Map<String, Set<String>> enabledTools, List<Issue> issues, String source) {
            this(enabledTools, issues, source, false);
        }

        /**
         * 该工具是否启用（组全名 + 工具名；组级 "*" 表示全组）。
         * {@code allOpen}（defaultAll）对<b>之后注册的组</b>同样放行——
         * "全权限"的契约覆盖注册时序（2026-10-23 修：快照曾把后注册组 fail-closed 全拒）。
         */
        public boolean allows(String groupFull, String tool) {
            if (allOpen) {
                return true;
            }
            Set<String> s = enabledTools.get(groupFull);
            return s != null && (s.contains("*") || s.contains(tool));
        }
    }

    private volatile Table current;
    private final ToolRegistry registry;

    private Permissions(ToolRegistry registry, Table initial) {
        this.registry = registry;
        this.current = initial;
    }

    /** 默认启动：完全工具访问权限（ID0）——含此后注册的组（allOpen）。 */
    public static Permissions defaultAll(ToolRegistry registry) {
        Map<String, Set<String>> all = new LinkedHashMap<>();
        for (ToolRegistry.Group g : registry.groups()) {
            all.put(g.full(), Set.of("*"));
        }
        return new Permissions(registry,
                new Table(all, List.of(), "default(id0, 全权限)", true));
    }

    /**
     * 从权限文件装载（分析 §二.五 表形态）。{@code input} 优先于 {@code bundled}：
     * 给了 input 就用 input（"优先输入的文件"），否则用自带。
     */
    public static Permissions fromFiles(ToolRegistry registry, Path input, Path bundled) {
        Path use = input != null && Files.isRegularFile(input) ? input : bundled;
        String source = use == null ? "none(全禁)" : use.getFileName().toString();
        Map<String, Set<String>> eff = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        /** 权限文件**明确提到**的组（提到即表态：生效或被关，都不再走自带默认）。 */
        Set<String> mentioned = new LinkedHashSet<>();

        if (use != null && Files.isRegularFile(use)) {
            try {
                Map<String, Object> raw = Json.parseObject(Files.readString(use));
                Object groups = raw.get("groups");
                if (groups instanceof Map<?, ?> gm) {
                    for (Map.Entry<?, ?> e : gm.entrySet()) {
                        String full = String.valueOf(e.getKey());
                        mentioned.add(full);
                        // 匹配失败 = WARN（配置跨档共用常态）；不锁死整表
                        if (registry.names().fullNames().contains(full) == false) {
                            issues.add(Issue.warn(full, "权限条目匹配不到任何工具组（" + source + "）"));
                            continue;
                        }
                        ToolRegistry.Group g = registry.groups().stream()
                                .filter(x -> x.full().equals(full)).findFirst().orElse(null);
                        boolean groupOn = true;
                        Set<String> tools = new LinkedHashSet<>();
                        if (e.getValue() instanceof Map<?, ?> entry) {
                            Object on = entry.get("enabled");
                            if (on instanceof Boolean b) {
                                groupOn = b;
                            }
                            Object tv = entry.get("tools");
                            if ("*".equals(String.valueOf(tv))) {
                                tools.add("*");
                            } else if (tv instanceof Map<?, ?> tm) {
                                for (Map.Entry<?, ?> te : tm.entrySet()) {
                                    if (Boolean.TRUE.equals(te.getValue())) {
                                        tools.add(String.valueOf(te.getKey()));
                                    }
                                }
                            }
                        }
                        // ALWAYS_ON 禁用 = 配置失败（分析 §二.五.3）
                        if (!groupOn && g != null && g.availability()
                                == ToolRegistry.Availability.ALWAYS_ON) {
                            issues.add(Issue.error(full,
                                    "配置失败: availability=ALWAYS_ON 不可禁用——该条禁用被忽略"));
                            groupOn = true;
                        }
                        if (groupOn && !tools.isEmpty()) {
                            eff.put(full, tools);
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                issues.add(Issue.error(String.valueOf(use), "权限文件不可读: " + e.getMessage()));
            }
        }
        // 自带默认（草案原文"无外部设置即使用其自身默认设置"）：builtin 来源的组，
        // 权限文件**没提到**就按其自身默认（注册表默认开）——不能因为用户文件只列了模组
        // 就让 core 自己的会话/记忆/任务/笔记全灭。外部组没提到仍然关（不猜第三方能力）。
        for (ToolRegistry.Group g : registry.groups()) {
            if (g.id().type() == snowleopard.core.names.Names.SourceType.BUILTIN
                    && !mentioned.contains(g.full())) {
                eff.put(g.full(), Set.of("*"));
            }
        }
        return new Permissions(registry, new Table(eff, issues, source));
    }

    /** 当前生效表。 */
    public Table table() {
        return current;
    }

    /**
     * 热更新：重建权限表，**只影响之后的调用**——旧 Table 引用仍可被在执行的调用继续使用
     * （不可变快照即不回溯的实现）。
     */
    public Table reload(Path input, Path bundled) {
        Permissions fresh = fromFiles(registry, input, bundled);
        this.current = fresh.current;
        return fresh.current;
    }

    /**
     * 未启用工具的可见性口径（供注入/搜索层用）：
     * {@code true} = 只能见名称；{@code false} = 完整可见。
     */
    public boolean namesOnly(String groupFull, String tool) {
        return !current.allows(groupFull, tool);
    }
}
