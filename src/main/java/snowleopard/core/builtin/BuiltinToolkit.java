package snowleopard.core.builtin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import snowleopard.core.memory.Periods;
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
            case "memory_default" -> Result.ok(home.memory().loadDefault(LocalDate.now()));
            case "memory_correct" -> memoryCorrect(p);
            case "memory_repair" -> home.rollup().repair(LocalDate.now());
            default -> unknown("memory", tool);
        };
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

    // ------------------------------------------------------------- 内部

    private static Result unknown(String group, String tool) {
        return Result.error("未知工具: builtin:" + BuiltinGroups.SOURCE_ID + ":" + group + ":" + tool
                + "（先 tool_search 看该组可用工具）");
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
}
