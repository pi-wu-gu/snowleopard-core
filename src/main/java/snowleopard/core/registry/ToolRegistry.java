package snowleopard.core.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import snowleopard.core.json.Json;
import snowleopard.core.names.Names;
import snowleopard.core.report.Issue;

/**
 * 工具组注册表（M2，2026-10-22 22:39 (UTC+8)）：装载、来源标注、注入、查询、搜索。
 *
 * <p>两级注入（分析 §一.1）：<b>组简介进上下文</b>（便宜，{@link #briefs()}），
 * <b>工具详解按需查</b>（{@link #help}）。"单轮 92% 预算花在工具介绍上"的解法就在这条分界。
 *
 * <p>搜索与权限的联动（§10.4，已采纳）：禁用的组/工具<b>搜得到名字、搜不到详解</b>
 * （与"未启用工具仅可见名称"同一原则）。
 */
public final class ToolRegistry {

    /** 可用性三档（分析 §二.五.3）。 */
    public enum Availability {
        /** 不可禁用：禁用尝试被拒。 */
        ALWAYS_ON,
        /** 常规。 */
        NORMAL,
        /** 默认禁用：可见名字，启用后才加载详解。 */
        DEFAULT_OFF;

        public static Availability parse(String s) {
            return switch (s == null ? "" : s.trim().toUpperCase(Locale.ROOT)) {
                case "", "NORMAL" -> NORMAL;
                case "ALWAYS_ON" -> ALWAYS_ON;
                case "DEFAULT_OFF" -> DEFAULT_OFF;
                default -> throw new IllegalArgumentException(
                        "未知 availability: " + s + "（可选 ALWAYS_ON | NORMAL | DEFAULT_OFF）");
            };
        }
    }

    /** 工具：详解 = 结构化 {@code usage + params}（缺一即拒载，§一.3）；简介可选。 */
    public record Tool(String name, String summary, String usage, Map<String, Object> params) {

        public boolean hasSummary() {
            return summary != null && !summary.isBlank();
        }
    }

    /** 工具组：简介必填；详解可选；分类路径由装载方拼进简介首行。 */
    public record Group(Names.Qualified id, String category, String summary, String details,
                        Availability availability, List<Tool> tools) {

        public String full() {
            return id.full();
        }
    }

    private final Names names = Names.empty();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();
    private final List<Issue> issues = new ArrayList<>();

    // ------------------------------------------------------------- 装载

    /** 注册（校验失败拒载留 ERROR，不中断整批——坏清单不该锁死全部）。 */
    public boolean register(Group g) {
        List<String> bad = validate(g);
        if (!bad.isEmpty()) {
            for (String b : bad) {
                issues.add(Issue.error(g.full(), "拒载: " + b));
            }
            return false;
        }
        Names.RegisterResult rr = names.register(g.id());
        if (!rr.accepted()) {
            issues.add(Issue.error(g.full(), "拒载: " + rr.rejectedBecause()));
            return false;
        }
        groups.put(g.full(), g);
        enabled.put(g.full(), g.availability() != Availability.DEFAULT_OFF);
        return true;
    }

    /**
     * 从单个清单 JSON 装载。形态：
     * {@code {"group":"组名","gid":"<local 必填的稳定 gid>","category":"a/b","summary":"…",
     * "details":"…","availability":"NORMAL","tools":[{"name":"…","summary":"…","usage":"…","params":{…}}]}}。
     * LOCAL 来源的 sourceId 用 manifest 的 {@code gid}（路径不进 ID，§10.1-1）。
     */
    public boolean loadManifest(String jsonText, Names.SourceType type, String modSourceId) {
        try {
            Map<String, Object> m = Json.parseObject(jsonText);
            String group = str(m.get("group"));
            String sourceId = switch (type) {
                case BUILTIN -> "core";
                case LOCAL -> str(m.get("gid"));
                case MOD -> modSourceId;
            };
            if (group.isBlank()) {
                issues.add(Issue.error("(清单)", "拒载: 缺 group"));
                return false;
            }
            List<Tool> tools = new ArrayList<>();
            Object rawTools = m.get("tools");
            if (rawTools instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> tm) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> t = (Map<String, Object>) tm;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = t.get("params") instanceof Map
                                ? (Map<String, Object>) t.get("params") : null;
                        tools.add(new Tool(str(t.get("name")), str(t.get("summary")),
                                str(t.get("usage")), params));
                    }
                }
            }
            Group g = new Group(
                    new Names.Qualified(type, sourceId, group),
                    str(m.get("category")),
                    str(m.get("summary")),
                    str(m.get("details")),
                    Availability.parse(str(m.get("availability"))),
                    tools);
            return register(g);
        } catch (IllegalArgumentException e) {
            issues.add(Issue.error("(清单)", "拒载: " + e.getMessage()));
            return false;
        }
    }

    private static List<String> validate(Group g) {
        List<String> bad = new ArrayList<>();
        if (g.summary() == null || g.summary().isBlank()) {
            bad.add("工具组必须带简介（summary）");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Tool t : g.tools()) {
            if (t.name() == null || t.name().isBlank()) {
                bad.add("工具名不能为空");
                continue;
            }
            if (!seen.add(t.name())) {
                bad.add("组内工具重名: " + t.name());
            }
            if (t.usage() == null || t.usage().isBlank() || t.params() == null) {
                bad.add("工具「" + t.name() + "」必须有详解（usage + params 结构化字段，§一.3）");
            }
        }
        return bad;
    }

    // ------------------------------------------------------------- 状态

    /** 启用/禁用。ALWAYS_ON 禁用被拒（§二.五.3——配置不许把核心能力关掉）。 */
    public boolean setEnabled(String groupFull, boolean on) {
        Group g = groups.get(groupFull);
        if (g == null) {
            return false;
        }
        if (!on && g.availability() == Availability.ALWAYS_ON) {
            issues.add(Issue.error(groupFull, "禁用被拒: availability=ALWAYS_ON（不可禁用）"));
            return false;
        }
        enabled.put(groupFull, on);
        return true;
    }

    public boolean isEnabled(String groupFull) {
        return Boolean.TRUE.equals(enabled.get(groupFull));
    }

    // ------------------------------------------------------------- 注入 / 查询

    /** 组简介注入行（便宜的那份）：分类 + 全名 + 简介。**不含详解**。 */
    public List<String> briefs() {
        List<String> out = new ArrayList<>();
        for (Group g : groups.values()) {
            String cat = g.category() == null || g.category().isBlank() ? "" : "[分类: " + g.category() + "] ";
            String head = cat + g.full() + " — " + firstLine(g.summary());
            out.add(isEnabled(g.full()) ? head : head + "（未启用，仅名称）");
        }
        return out;
    }

    /**
     * 统一查询入口（按需的那份）：返回组/工具详解。
     * 禁用对象**只回名称**（§10.4：搜得到名字、搜不到详解）。
     */
    public String help(String name, Map<String, String> aliases) {
        String groupPart = name;
        String toolPart = null;
        int dot = name.indexOf('.');
        if (dot > 0) {
            groupPart = name.substring(0, dot);
            toolPart = name.substring(dot + 1);
        }
        Object r = names.resolve(groupPart, aliases);
        if (r == null) {
            return null;
        }
        if (r instanceof Names.Ambiguous a) {
            return a.message();
        }
        Group g = groups.get(r);
        if (g == null) {
            return null;
        }
        if (!isEnabled(g.full())) {
            return g.full() + "（未启用，仅名称）";
        }
        StringBuilder b = new StringBuilder();
        if (g.category() != null && !g.category().isBlank()) {
            b.append("[分类: ").append(g.category()).append("]\n");
        }
        b.append("组 ").append(g.full()).append("\n简介: ").append(g.summary()).append('\n');
        if (g.details() != null && !g.details().isBlank()) {
            b.append("详解: ").append(g.details()).append('\n');
        }
        for (Tool t : g.tools()) {
            if (toolPart != null && !t.name().equals(toolPart)) {
                continue;
            }
            b.append("\n工具 ").append(g.full()).append('.').append(t.name()).append('\n');
            b.append("  简介: ").append(t.hasSummary() ? t.summary() : "（无简介，看详解）").append('\n');
            b.append("  用法: ").append(t.usage()).append('\n');
            b.append("  参数: ").append(t.params()).append('\n');
        }
        return b.toString();
    }

    /**
     * 关键词搜索：命中简介/详解（禁用对象只搜名字）。来源过滤按 {@code sourceId} 或 {@code type:sourceId}。
     */
    public List<String> search(String query, String sourceFilter) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        for (Group g : groups.values()) {
            if (!sourceOk(g.id(), sourceFilter)) {
                continue;
            }
            boolean on = isEnabled(g.full());
            // 组级：名字永远可搜；简介/详解仅启用时可搜
            String groupHay = (g.full() + " " + (on ? g.summary() + " " + nvl(g.details()) : ""))
                    .toLowerCase(Locale.ROOT);
            if (q.isEmpty() || groupHay.contains(q)) {
                out.add(on ? g.full() + " — " + firstLine(g.summary()) : g.full() + "（未启用，仅名称）");
            }
            for (Tool t : g.tools()) {
                String toolHay = (g.full() + "." + t.name() + " "
                        + (on ? t.summary() + " " + t.usage() : "")).toLowerCase(Locale.ROOT);
                if (q.isEmpty() || toolHay.contains(q)) {
                    out.add(on
                            ? g.full() + "." + t.name() + " — " + (t.hasSummary() ? firstLine(t.summary()) : "（无简介）")
                            : g.full() + "." + t.name() + "（未启用，仅名称）");
                }
            }
        }
        return out;
    }

    private static boolean sourceOk(Names.Qualified id, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String f = filter.trim().toLowerCase(Locale.ROOT);
        return id.sourceId().toLowerCase(Locale.ROOT).equals(f)
                || (id.type().wire() + ":" + id.sourceId()).toLowerCase(Locale.ROOT).equals(f);
    }

    // ------------------------------------------------------------- 杂项

    public Names names() {
        return names;
    }

    public List<Issue> issues() {
        return java.util.Collections.unmodifiableList(issues);
    }

    public Collection<Group> groups() {
        return java.util.Collections.unmodifiableCollection(groups.values());
    }

    public int toolCount() {
        return groups.values().stream().mapToInt(g -> g.tools().size()).sum();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
