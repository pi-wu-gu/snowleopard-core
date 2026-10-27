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
 * <p>搜索与权限的联动（分析 §10.4，已采纳）：禁用的组/工具<b>搜得到名字、搜不到详解</b>
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

    /** 工具：详解 = 结构化 {@code usage + params}（缺一即拒载，分析 §一.3）；简介可选。 */
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
    /** 无组工具合成组（{@code <来源>:solo}）：注入时单列，不算常规组。 */
    private final Set<String> soloFulls = new java.util.LinkedHashSet<>();

    // ------------------------------------------------------------- 装载

    /** 注册（校验失败拒载留 ERROR，不中断整批——坏清单不该锁死全部）。 */
    public boolean register(Group g) {
        return register(g, false);
    }

    /**
     * 注册（{@code solo=true} 是装载器为无组工具合成的组，只此一条入口，不开第二条注册路径）。
     *
     * <p><b>{@code solo} 是保留组名</b>：真组撞名直接拒载——否则无组工具区与真组同名，
     * 模型无法区分"这组是真的"还是"这是散件"。
     *
     * <p><b>详解目录缺失只记 WARN 不拒载</b>（2026-10-27 裁决）：缺目录的后果是模型拼不出工具路径
     * （效果等于没查），属可用性问题而非安全问题；拒载会让"某仓库还没补 details"直接阻断构建。
     * 各仓 check 做硬断言，把"该补"落在 CI 而不是装载期。
     */
    public boolean register(Group g, boolean solo) {
        if (!solo && "solo".equals(g.id().group())) {
            issues.add(Issue.error(g.full(), "拒载: 「solo」是保留组名（无组工具专用）"));
            return false;
        }
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
        if (solo) {
            soloFulls.add(g.full());
        }
        warnDetails(g);
        return true;
    }

    /** 组详解必须带"功能 + 完整目录"：缺/不全只记 WARN（见 {@link #register(Group, boolean)} 的理由）。 */
    private void warnDetails(Group g) {
        String details = g.details() == null ? "" : g.details();
        if (details.isBlank()) {
            issues.add(Issue.warn(g.full(), "详解缺失：应写「功能 + 目录（全部成员）+ 使用要点」——"
                    + "缺目录时模型拼不出可调用路径，逐级查询等于白查"));
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Tool t : g.tools()) {
            if (!details.contains(t.name())) {
                missing.add(t.name());
            }
        }
        if (!missing.isEmpty()) {
            issues.add(Issue.warn(g.full(), "详解未列出成员 " + missing + "：目录必须完整"
                    + "（模型靠目录确认工具路径）"));
        }
    }

    /**
     * 从单个清单 JSON 装载。形态：
     * {@code {"group":"组名","gid":"<local 必填的稳定 gid>","category":"a/b","summary":"…",
     * "details":"…","availability":"NORMAL","tools":[{"name":"…","summary":"…","usage":"…","params":{…}}]}}。
     * LOCAL 来源的 sourceId 用 manifest 的 {@code gid}（路径不进 ID，分析 §10.1-1）。
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
                bad.add("工具「" + t.name() + "」必须有详解（usage + params 结构化字段，分析 §一.3）");
            }
        }
        return bad;
    }

    // ------------------------------------------------------------- 状态

    /** 启用/禁用。ALWAYS_ON 禁用被拒（分析 §二.五.3——配置不许把核心能力关掉）。 */
    /**
     * 按来源注销（M6：模组卸载）。返回被移除的全名清单（幂等）。
     * 短名随注销释放，同全名可重新注册（卸载重装是正常生命周期）。
     */
    public List<String> unregisterSource(Names.SourceType type, String sourceId) {
        List<String> removed = new ArrayList<>();
        for (String full : new ArrayList<>(groups.keySet())) {
            Group g = groups.get(full);
            if (g.id().type() == type && g.id().sourceId().equals(sourceId)) {
                groups.remove(full);
                enabled.remove(full);
                names.unregister(full);
                removed.add(full);
            }
        }
        return removed;
    }

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
     * 组清单<b>按来源分节</b>（2026-10-27 15:07 裁决）：跨来源重名是常态（谁都能起 {@code move}），
     * 所以注入只给全名；来源已在全名里，不再另打分类标签。
     *
     * <p>无组工具（{@code <来源>:solo}）不进本节——它们在 {@link #injectionBlock()} 里单列。
     */
    public List<String> briefsBySource() {
        return briefsBySource(null);
    }

    /**
     * 按来源过滤的组清单（{@code sourceFilter} 支持 {@code builtin} / {@code mod:snowleopard} 两种写法）。
     * 过滤只影响"列哪些"，不影响"哪些可用"——权限与启用态另算。
     */
    public List<String> briefsBySource(String sourceFilter) {
        Map<String, List<Group>> bySource = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            if (soloFulls.contains(g.full())) {
                continue;
            }
            String source = g.id().type().wire() + ":" + g.id().sourceId();
            if (!sourceMatches(sourceFilter, source)) {
                continue;
            }
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(g);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Group>> e : bySource.entrySet()) {
            out.add("[" + e.getKey() + "] " + e.getValue().size() + " 组");
            for (Group g : e.getValue()) {
                String head = "- " + g.full() + " — " + firstLine(g.summary()) + "  (" + g.tools().size() + ")";
                out.add(isEnabled(g.full()) ? head : head + "（未启用，仅名称）");
            }
        }
        return out;
    }

    /** 组级视图（逐级查询的第二跳）：组身份 + 详解 + 成员名单（名字 + 一行简介，<b>不含参数</b>）。 */
    public String members(String name, Map<String, String> aliases) {
        Names.Resolution r = names.resolve(name, aliases);
        if (r instanceof Names.Resolution.NotFound) {
            return "没有名为 " + name + " 的组（用 tool_list 看可用组；要看某个工具用「组.工具」）";
        }
        if (r instanceof Names.Ambiguous a) {
            return a.message();
        }
        Group g = groups.get(((Names.Resolution.Found) r).full());
        if (g == null) {
            return "没有名为 " + name + " 的组（注册表与名字表不一致——请报告此状态）";
        }
        if (!isEnabled(g.full())) {
            return g.full() + "（未启用，仅名称）";
        }
        StringBuilder b = new StringBuilder();
        b.append("组 ").append(g.full()).append('\n');
        b.append("简介: ").append(g.summary()).append('\n');
        if (g.details() != null && !g.details().isBlank()) {
            b.append("详解:\n").append(g.details().strip()).append('\n');
        }
        b.append("成员 ").append(g.tools().size())
                .append(" 个（要看某个工具的用法/参数：tool_help(\"").append(g.id().group())
                .append(".工具名\"），已加入工作集的组下一轮起可直接调用）:\n");
        for (Tool t : g.tools()) {
            b.append("- ").append(t.name()).append(" — ")
                    .append(t.hasSummary() ? t.summary() : "（无简介）").append('\n');
        }
        return b.toString();
    }

    /** 无组工具合成组（{@code <来源>:solo}）——仅供注入与查询单列，不算常规组。 */
    public List<Group> soloGroups() {
        return soloGroups(null);
    }

    /** 同上，可按来源过滤（{@code builtin} / {@code mod:snowleopard}）。 */
    public List<Group> soloGroups(String sourceFilter) {
        List<Group> out = new ArrayList<>();
        for (String full : soloFulls) {
            Group g = groups.get(full);
            if (g != null && sourceMatches(sourceFilter, g.id().type().wire() + ":" + g.id().sourceId())) {
                out.add(g);
            }
        }
        return out;
    }

    public boolean isSolo(String groupFull) {
        return soloFulls.contains(groupFull);
    }

    /**
     * 每轮进上下文的那一块（{@code 用法说明 + 组清单 + 无组工具}）。
     *
     * <p><b>为什么由 core 生成而不是宿主拼</b>：多宿主共用一段文案，放宿主就会各写一版、措辞漂移；
     * 而这段话直接决定模型会不会去用逐级查询——它错了，整套机制等于没接。
     *
     * <p>**组内工具一个都不列**（命题的硬要求）：给了就退回"平铺"，逐级查询失去意义。
     */
    public String injectionBlock() {
        StringBuilder b = new StringBuilder();
        b.append("【工具的分组方式】\n")
                .append("工具按「组」组织：组有名字、来源、功能说明和一份目录（组内有哪些工具）。\n")
                .append("本轮只给你组清单；组内工具的用法不在这里，用 tool_help 查")
                .append("（知道组名就能查，也能按组内目录只取其中一个）。\n")
                .append("查过的组会加入本轮工具表，下一轮起可直接调用；不再需要时用 tool_scope remove 移出（省上下文）。\n\n");
        b.append("【工具组清单】").append(groups.size() - soloFulls.size())
                .append(" 组 · 按来源分节（跨来源重名用全名区分）\n");
        for (String line : briefsBySource()) {
            b.append(line).append('\n');
        }
        List<Group> solos = soloGroups();
        if (!solos.isEmpty()) {
            b.append("\n【无组工具】（没有所属组，简介与用法直接给全）\n");
            for (Group g : solos) {
                b.append("- ").append(g.full()).append(" — ").append(g.id().sourceId())
                        .append(" · 无组工具（").append(g.tools().size()).append(" 个）\n");
                for (Tool t : g.tools()) {
                    b.append("  · ").append(t.name()).append(" — ")
                            .append(t.hasSummary() ? t.summary() : "（无简介）").append('\n');
                    b.append("    用法: ").append(t.usage()).append('\n');
                    b.append("    参数: ").append(t.params()).append('\n');
                }
            }
        }
        return b.toString();
    }

    /**
     * 统一查询入口（按需的那份）：返回组/工具详解。
     * 禁用对象**只回名称**（分析 §10.4：搜得到名字、搜不到详解）。
     */
    public String help(String name, Map<String, String> aliases) {
        String groupPart = name;
        String toolPart = null;
        int dot = name.indexOf('.');
        if (dot > 0) {
            groupPart = name.substring(0, dot);
            toolPart = name.substring(dot + 1);
        }
        Names.Resolution r = names.resolve(groupPart, aliases);
        if (r instanceof Names.Resolution.NotFound) {
            // M7：注销/未注册要"报不存在"而不是静默 null（调用方漏判 null 就是假成功温床）
            return "工具不存在: " + groupPart + "（未注册或已注销——用 tool_list 看可用组）";
        }
        if (r instanceof Names.Ambiguous a) {
            return a.message();
        }
        String full = ((Names.Resolution.Found) r).full();
        Group g = groups.get(full);
        if (g == null) {
            return "工具不存在: " + full + "（注册表与名字表不一致——请报告此状态）";
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
        return sourceMatches(filter, id.type().wire() + ":" + id.sourceId());
    }

    /** 来源过滤：接受 {@code sourceId}（{@code snowleopard}）或 {@code type:sourceId}（{@code mod:snowleopard}）。 */
    private static boolean sourceMatches(String filter, String sourceKey) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String f = filter.trim().toLowerCase(Locale.ROOT);
        String key = sourceKey.toLowerCase(Locale.ROOT);
        String sid = key.substring(key.indexOf(':') + 1);
        return key.equals(f) || sid.equals(f);
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
