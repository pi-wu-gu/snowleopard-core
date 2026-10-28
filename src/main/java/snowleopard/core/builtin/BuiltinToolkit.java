package snowleopard.core.builtin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import snowleopard.core.memory.Periods;
import snowleopard.core.names.Names;
import snowleopard.core.registry.ToolExposure;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.result.Result;
import snowleopard.core.session.SessionGate;
import snowleopard.core.todo.TodoStore;

/**
 * 内置工具组的执行面（2026-10-23 19:20 (UTC+8)）。
 *
 * <p>注册表只装 schema（注入/查询用），<b>执行按 {@code 组名 + 工具名 + 参数}</b> 路由到这里。
 * 组名未知 = 宿主装配错误 → 抛 {@link IllegalArgumentException}（不是模型输入问题）；
 * 工具名未知 / 参数缺失 = <b>模型输入问题</b> → 返回 {@link Result.Error}（带可用清单提示），
 * 绝不用字符串假装成功。
 *
 * <p>每个方法只碰 {@link BuiltinHome} 给的调用 ID 家的 Store——跨 ID 在能力层不存在（红线 3）。
 */
public final class BuiltinToolkit {

    private final BuiltinHome home;

    public BuiltinToolkit(BuiltinHome home) {
        if (home == null) {
            throw new IllegalArgumentException("home 必填（调用 ID 的家——不猜、不共享）");
        }
        this.home = home;
    }

    /** 统一入口：组名是内置四组之一，工具名在该组清单内。 */
    public Result call(String group, String tool, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        return switch (String.valueOf(group)) {
            case "session" -> session(tool, p);
            case "memory" -> memory(tool, p);
            case "todo" -> todo(p, tool);
            case "notes" -> notes(tool, p);
            case "wake" -> wake(tool, p);
            case "group" -> group(tool, p);
            case "tool" -> catalog(tool, p);
            default -> throw new IllegalArgumentException("未知内置组: " + group
                    + "（可选 " + String.join(" | ", BuiltinGroups.GROUP_NAMES) + "）");
        };
    }

    // ------------------------------------------------------------- 会话

    private Result session(String tool, Map<String, Object> p) {
        SessionGate s = home.session();
        return switch (tool) {
            case "session_yield" -> s.yield();
            case "session_usage" -> {
                SessionGate.Usage u = s.usage();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("roundTokens", u.roundTokens());
                d.put("lastUtteranceTokens", u.lastUtteranceTokens());
                d.put("totalTokens", u.totalTokens());
                d.put("turns", u.turns());
                // 余量口径与注入行同源（-1 = 无上限，不显示 0——星期五 ConfigPanel 的 0 坑）
                d.put("remainingTokens", s.remainingTokens());
                d.put("tokensLimited", s.remainingTokens() >= 0);
                d.put("stopReason", s.stopReason());
                d.put("injection", s.injectionLine());
                yield Result.ok(d);
            }
            default -> unknown("session", tool);
        };
    }

    // ------------------------------------------------------------- 记忆

    private Result memory(String tool, Map<String, Object> p) {
        return switch (tool) {
            case "memory_read" -> memoryRead(p);
            case "memory_list" -> memoryList(p);
            case "memory_default" -> Result.ok(home.memory().loadDefault(LocalDate.now()));
            case "memory_correct" -> memoryCorrect(p);
            case "memory_repair" -> home.rollup().repair(LocalDate.now());
            default -> unknown("memory", tool);
        };
    }

    /** 列出可用记忆期号（天/轮），供模型发现可读内容。 */
    private Result memoryList(Map<String, Object> p) {
        String level = str(p, "level");
        if (level == null || "day".equalsIgnoreCase(level)) {
            var days = home.memory().keys(Periods.Level.DAY);
            // 也列轮次（按天分组）
            var rounds = home.rawLog().listRounds();
            var byDay = new java.util.LinkedHashMap<String, java.util.List<String>>();
            for (var r : rounds) {
                String d = snowleopard.core.memory.RawLog.dayOf(r.endedTs()).toString();
                byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(r.key());
            }
            var out = new java.util.LinkedHashMap<String, Object>();
            out.put("days", days);
            out.put("rounds", byDay);
            return Result.ok(out);
        }
        if ("round".equalsIgnoreCase(level)) {
            var rounds = home.rawLog().listRounds();
            var keys = new java.util.ArrayList<String>();
            for (var r : rounds) {
                keys.add(r.key() + " (" + r.entries() + " entries)");
            }
            return Result.ok(java.util.Map.of("rounds", keys));
        }
        // week/month/quarter/year
        try {
            Periods.Level lv = Periods.Level.of(level);
            // 键名统一小写：传 "WEEK" 时旧写法会拼出 "WEEKs"（能跑，但键名大小写随调用方漂移）
            return Result.ok(java.util.Map.of(level.toLowerCase(java.util.Locale.ROOT) + "s",
                    home.memory().keys(lv)));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private Result memoryRead(Map<String, Object> p) {
        String level = str(p, "level");
        String period = str(p, "period");
        if (level == null) {
            return Result.error("memory_read 需要 level（day|week|month|quarter|year|round）");
        }
        if (period == null) {
            return Result.error("memory_read 需要 period（期号；level=round 时传轮档名）");
        }
        if ("round".equalsIgnoreCase(level)) {
            String sub = str(p, "sub");
            if (sub != null) {
                try {
                    return home.rawLog().readEntry(period, Integer.parseInt(sub));
                } catch (NumberFormatException e) {
                    return Result.error("sub 必须是整数（轮内条目序号）: " + sub);
                }
            }
            return Result.ok(home.rawLog().readRound(period));
        }
        Periods.Level lv;
        try {
            lv = Periods.Level.of(level);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage() + "（day|week|month|quarter|year|round）");
        }
        return home.memory().readRouted(lv, period);
    }

    private Result memoryCorrect(Map<String, Object> p) {
        String action = str(p, "action");
        String token = str(p, "confirm_token");
        if (action == null) {
            return Result.error("memory_correct 需要 action（add|remove）");
        }
        if ("add".equalsIgnoreCase(action)) {
            String ref = str(p, "target_ref");
            String note = str(p, "note");
            if (ref == null || note == null) {
                return Result.error("memory_correct add 需要 target_ref 与 note");
            }
            return home.corrections().add(ref, note, token);
        }
        if ("remove".equalsIgnoreCase(action)) {
            String id = str(p, "id");
            if (id == null) {
                return Result.error("memory_correct remove 需要 id");
            }
            return home.corrections().remove(id, token);
        }
        return Result.error("未知 action: " + action + "（可选 add | remove）");
    }

    // ------------------------------------------------------------- 任务清单

    private Result todo(Map<String, Object> p, String tool) {
        TodoStore t = home.todos();
        switch (tool) {
            case "todo_add": {
                String text = str(p, "text");
                if (text == null) {
                    return Result.error("todo_add 需要 text");
                }
                return t.add(text);
            }
            case "todo_add_sub": {
                String parent = str(p, "parent_id");
                String text = str(p, "text");
                if (parent == null || text == null) {
                    return Result.error("todo_add_sub 需要 parent_id 与 text");
                }
                return t.addSub(parent, text);
            }
            case "todo_mark": {
                String id = str(p, "id");
                String status = str(p, "status");
                if (id == null || status == null) {
                    return Result.error("todo_mark 需要 id 与 status（PENDING|DONE|BLOCKED|FAILED）");
                }
                try {
                    return t.mark(id, TodoStore.Status.valueOf(status.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return Result.error("未知状态: " + status + "（可选 PENDING|DONE|BLOCKED|FAILED）");
                }
            }
            case "todo_list": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (TodoStore.Item i : t.list()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.id());
                    m.put("text", i.text());
                    m.put("status", i.status().name());
                    m.put("parentId", i.parentId());
                    m.put("createdTs", TodoStore.formatTs(i.createdTs()));
                    m.put("updatedTs", TodoStore.formatTs(i.updatedTs()));
                    out.add(m);
                }
                return Result.ok(out);
            }
            case "todo_delete": {
                String id = str(p, "id");
                return id == null ? Result.error("todo_delete 需要 id") : t.delete(id);
            }
            case "todo_purge": {
                String id = str(p, "id");
                return id == null ? Result.error("todo_purge 需要 id") : t.purge(id);
            }
            default:
                return unknown("todo", tool);
        }
    }

    // ------------------------------------------------------------- 笔记

    private Result notes(String tool, Map<String, Object> p) {
        var n = home.notes();
        switch (tool) {
            case "note_mkdir": {
                String path = str(p, "path");
                return path == null ? Result.error("note_mkdir 需要 path") : n.mkdir(path);
            }
            case "note_create": {
                String path = str(p, "path");
                String content = str(p, "content");
                if (path == null || content == null) {
                    return Result.error("note_create 需要 path 与 content");
                }
                return n.write(path, content);
            }
            case "note_append": {
                String path = str(p, "path");
                String content = str(p, "content");
                if (path == null || content == null) {
                    return Result.error("note_append 需要 path 与 content");
                }
                return n.append(path, content);
            }
            case "note_read": {
                String path = str(p, "path");
                return path == null ? Result.error("note_read 需要 path") : n.read(path);
            }
            case "note_search": {
                String kw = str(p, "keyword");
                return kw == null ? Result.error("note_search 需要 keyword") : n.search(kw);
            }
            case "note_delete": {
                String path = str(p, "path");
                if (path == null) {
                    return Result.error("note_delete 需要 path");
                }
                return n.delete(path, str(p, "confirm_token"));
            }
            default:
                return unknown("notes", tool);
        }
    }

    // ------------------------------------------------------------- 唤醒

    /**
     * 唤起（裁决：深度 1 / 在运行则失败 / 黑名单属被唤起方）。
     *
     * <p>{@code from} 与唤醒链都从 {@link BuiltinHome} 取——<b>工具参数里没有 from</b>，
     * 模型改不了"我是谁"和"我离根有多远"。
     */
    private Result wake(String tool, Map<String, Object> p) {
        var w = home.wake();
        return switch (tool) {
            case "wake" -> {
                String to = str(p, "model_id");
                if (to == null) {
                    yield Result.error("wake 需要 model_id");
                }
                yield w.request(new snowleopard.core.wake.WakeStore.Chain(home.wakeChain()),
                        home.callerId(), to, str(p, "prompt"), str(p, "reason"),
                        str(p, "client_req_id"));
            }
            case "wake_status" -> {
                String reqId = str(p, "req_id");
                yield reqId == null ? Result.error("wake_status 需要 req_id")
                        : w.status(home.callerId(), reqId);
            }
            case "wake_deny" -> {
                String id = str(p, "model_id");
                if (id == null) {
                    yield Result.error("wake_deny 需要 model_id");
                }
                Object on = p.get("on");
                boolean flag = on == null || Boolean.parseBoolean(String.valueOf(on));
                yield w.deny(home.callerId(), id, flag);
            }
            case "wake_policy" -> w.policy(home.callerId());
            default -> unknown("wake", tool);
        };
    }

    // ------------------------------------------------------------- 群组

    private Result group(String tool, Map<String, Object> p) {
        var g = home.groups();
        String actor = home.callerId();
        switch (tool) {
            case "group_create": {
                String name = str(p, "name");
                return name == null ? Result.error("group_create 需要 name") : g.create(actor, name);
            }
            case "group_disband": {
                String gid = str(p, "gid");
                return gid == null ? Result.error("group_disband 需要 gid") : g.disband(actor, gid);
            }
            case "group_add": {
                String gid = str(p, "gid");
                String who = str(p, "model_id");
                if (gid == null || who == null) {
                    return Result.error("group_add 需要 gid 与 model_id");
                }
                return g.add(actor, gid, who);
            }
            case "group_remove": {
                String gid = str(p, "gid");
                String who = str(p, "model_id");
                if (gid == null || who == null) {
                    return Result.error("group_remove 需要 gid 与 model_id");
                }
                return g.remove(actor, gid, who);
            }
            case "group_grant": {
                String gid = str(p, "gid");
                String who = str(p, "model_id");
                String role = str(p, "role");
                if (gid == null || who == null || role == null) {
                    return Result.error("group_grant 需要 gid、model_id 与 role（admin|member）");
                }
                return g.grant(actor, gid, who, role);
            }
            case "group_leave": {
                String gid = str(p, "gid");
                return gid == null ? Result.error("group_leave 需要 gid") : g.leave(actor, gid);
            }
            case "group_send": {
                String gid = str(p, "gid");
                String text = str(p, "text");
                if (gid == null || text == null) {
                    return Result.error("group_send 需要 gid 与 text");
                }
                return g.send(actor, gid, text, str(p, "ref"), str(p, "client_msg_id"));
            }
            case "group_read": {
                String gid = str(p, "gid");
                if (gid == null) {
                    return Result.error("group_read 需要 gid");
                }
                return g.read(actor, gid, intOf(p, "limit", 20), longOf(p, "since_seq", -1));
            }
            case "group_list":
                return g.list(actor);
            case "group_search": {
                String gid = str(p, "gid");
                String kw = str(p, "keyword");
                if (gid == null || kw == null) {
                    return Result.error("group_search 需要 gid 与 keyword");
                }
                return g.search(actor, gid, kw, longOf(p, "since_ts", 0),
                        longOf(p, "until_ts", 0), intOf(p, "limit", 20));
            }
            default:
                return unknown("group", tool);
        }
    }

    // ------------------------------------------------------------- 工具目录

    /** tool_help / tool_scope 一次最多处理几个目标：防"一次要把全量拉回来"退化成平铺。 */
    private static final int MAX_TARGETS = 4;

    /**
     * 工具目录（第 7 组，2026-10-27 16:10 (UTC+8)）：列组 / 查详解 / 增删工作集。
     *
     * <p>三个工具都是**薄包装**——数据在注册表、状态在工作集；这里只做参数校验与"下一步"提示。
     * 查到就取用（pin）：命中即把该组（或该工具）记入工作集，宿主下一轮声明时才带签名（解锁可回）。
     */
    private Result catalog(String tool, Map<String, Object> p) {
        return switch (tool) {
            case "tool_list" -> toolList(p);
            case "tool_help" -> toolHelp(p);
            case "tool_scope" -> toolScope(p);
            default -> unknown("tool", tool);
        };
    }

    private Result toolList(Map<String, Object> p) {
        ToolRegistry reg = home.registry();
        String source = str(p, "source");
        StringBuilder b = new StringBuilder("工具组清单（按来源分节）：\n");
        List<String> lines = reg.briefsBySource(source);
        if (lines.isEmpty()) {
            b.append("（没有匹配来源「").append(source).append("」的已装载组）\n");
        }
        for (String line : lines) {
            b.append(line).append('\n');
        }
        List<ToolRegistry.Group> solos = reg.soloGroups(source);
        if (!solos.isEmpty()) {
            b.append("\n【无组工具】\n");
            for (ToolRegistry.Group g : solos) {
                b.append("- ").append(g.full()).append(" — ").append(g.id().sourceId())
                        .append(" · 无组工具（").append(g.tools().size()).append(" 个）\n");
            }
        }
        b.append("\n用法：tool_help(\"组名\") 看组详解与成员（查到即加入工作集，下一轮可调用）；")
                .append("tool_scope add|remove 显式增删；写「组.工具」可只取一个。");
        return Result.ok(b.toString());
    }

    private Result toolHelp(Map<String, Object> p) {
        String targets = str(p, "targets");
        if (targets == null) {
            return Result.error("tool_help 需要 targets（组名 或 组.工具，逗号分隔，一次最多 " + MAX_TARGETS + " 个）");
        }
        List<String> ts = splitTargets(targets);
        if (ts.isEmpty()) {
            return Result.error("tool_help 需要 targets（组名 或 组.工具）");
        }
        if (ts.size() > MAX_TARGETS) {
            return Result.error("一次最多查 " + MAX_TARGETS + " 个（防止把全量清单一次拉回来）——请分批查");
        }
        ToolRegistry reg = home.registry();
        ToolExposure ex = home.exposure();
        String me = home.callerId();
        StringBuilder b = new StringBuilder();
        int pinned = 0;
        for (String t : ts) {
            int dot = t.indexOf('.');
            String text;
            if (dot > 0) {
                String groupFull = resolveGroup(t.substring(0, dot));
                String toolName = t.substring(dot + 1);
                text = reg.help(t, Map.of());
                if (groupFull != null && toolExists(groupFull, toolName)) {
                    ex.pinTool(me, groupFull, toolName);
                    pinned++;
                }
            } else {
                String groupFull = resolveGroup(t);
                text = reg.members(t, Map.of());
                if (groupFull != null) {
                    ex.pinGroup(me, groupFull);
                    pinned++;
                }
            }
            b.append("=== ").append(t).append(" ===\n").append(text);
            if (!text.endsWith("\n")) {
                b.append('\n');
            }
        }
        b.append("\n（已取用 ").append(pinned).append(" 项：**下一轮**请求起这些工具带签名，本轮别等；")
                .append("不再需要时 tool_scope remove）");
        return Result.ok(b.toString());
    }

    private Result toolScope(Map<String, Object> p) {
        String action = str(p, "action");
        String target = str(p, "target");
        if (action == null || target == null) {
            return Result.error("tool_scope 需要 action（add|remove）与 target（组名 或 组.工具，逗号分隔）");
        }
        String act = action.toLowerCase(Locale.ROOT);
        if (!act.equals("add") && !act.equals("remove")) {
            return Result.error("action 只能是 add 或 remove: " + action);
        }
        ToolExposure ex = home.exposure();
        String me = home.callerId();
        StringBuilder b = new StringBuilder();
        for (String t : splitTargets(target)) {
            int dot = t.indexOf('.');
            if (dot > 0) {
                String groupFull = resolveGroup(t.substring(0, dot));
                String toolName = t.substring(dot + 1);
                if (groupFull == null) {
                    b.append("- ").append(t).append("：没有这个组（tool_list 看可用组）\n");
                    continue;
                }
                if (!toolExists(groupFull, toolName)) {
                    b.append("- ").append(t).append("：该组没有这个工具\n");
                    continue;
                }
                if (act.equals("add")) {
                    ex.pinTool(me, groupFull, toolName);
                } else {
                    ex.unpinTool(me, groupFull, toolName);
                }
                b.append("- ").append(act).append(' ').append(groupFull).append('.').append(toolName).append("：OK\n");
            } else {
                String groupFull = resolveGroup(t);
                if (groupFull == null) {
                    b.append("- ").append(t).append("：没有这个组（tool_list 看可用组）\n");
                    continue;
                }
                if (act.equals("add")) {
                    ex.pinGroup(me, groupFull);
                } else if (!ex.unpinGroup(me, groupFull)) {
                    b.append("- ").append(groupFull).append("：本来就没取用\n");
                    continue;
                }
                b.append("- ").append(act).append(' ').append(groupFull).append("：OK\n");
            }
        }
        b.append("工作集：组 ").append(ex.pinnedGroups(me))
                .append("；单工具 ").append(ex.pinnedTools(me));
        return Result.ok(b.toString());
    }

    private static List<String> splitTargets(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 短名/全名 → 组全名；歧义与不存在都返回 null（消息由 {@code members/help} 给，不在这里猜）。 */
    private String resolveGroup(String name) {
        Names.Resolution r = home.registry().names().resolve(name, Map.of());
        if (r instanceof Names.Resolution.Found f) {
            return f.full();
        }
        return null;
    }

    private boolean toolExists(String groupFull, String toolName) {
        for (ToolRegistry.Group g : home.registry().groups()) {
            if (!g.full().equals(groupFull)) {
                continue;
            }
            for (ToolRegistry.Tool t : g.tools()) {
                if (t.name().equals(toolName)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------- 内部

    private static Result unknown(String group, String tool) {
        return Result.error("未知工具: builtin:" + BuiltinGroups.SOURCE_ID + ":" + group + ":" + tool
                + "（用 tool_list 看有哪些组，再用 tool_help 查用法）");
    }

    /** 取字符串参数：缺省/空白 → null（不把空串当合法值）。 */
    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intOf(Map<String, Object> p, String key, int dflt) {
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long longOf(Map<String, Object> p, String key, long dflt) {
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return dflt;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
