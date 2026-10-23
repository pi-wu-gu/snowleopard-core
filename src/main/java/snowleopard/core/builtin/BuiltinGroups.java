package snowleopard.core.builtin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.Version;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.spi.ToolGroupProvider;
import snowleopard.core.registry.ToolRegistry.Availability;
import snowleopard.core.registry.ToolRegistry.Tool;

/**
 * 四个自带工具组的清单（2026-10-23 19:20 (UTC+8)，分析 §三 / §4.4）。
 *
 * <p>来源固定 {@code builtin:core:*}——<b>限定名由装载器拼</b>（provider 不构造），
 * 与外部模组走同一条 {@link ToolGroupProvider} 路径、同一套清单校验。
 *
 * <p>可用性照分析 §二.五.3：<b>会话与记忆 ALWAYS_ON</b>（不可禁用——它们是"能跑"的底线），
 * 任务清单与笔记 NORMAL（可以关）。
 *
 * <p>参数 schema 形态与注册表一致：{@code params} 必须非空（详解 = usage + params 结构化，
 * 缺一即拒载）——所以无参工具也给 {@code {"type":"object","required":[]}} 而不是 null。
 */
public final class BuiltinGroups {

    /** 内置组的来源 ID（限定名第二段）。 */
    public static final String SOURCE_ID = "core";

    /** 四组短名（顺序即装载顺序：会话 → 记忆 → 任务 → 笔记）。 */
    public static final List<String> GROUP_NAMES = List.of("session", "memory", "todo", "notes");

    /** 四组 provider（每次调用返回新实例——provider 无状态，注册表持有描述即可）。 */
    public static List<ToolGroupProvider> all() {
        return List.of(session(), memory(), todo(), notes());
    }

    // ------------------------------------------------------------- 会话

    /** 会话管理（分析 §三.一）：结束本轮 + 用量查询。 */
    public static ToolGroupProvider session() {
        return provider("session", "builtin/session", Availability.ALWAYS_ON,
                "会话管理（不可禁用）：结束本轮、查看消耗与余量",
                "session_yield 主动收束本轮（区别于轮次上限耗尽的强制收束）；"
                        + "session_usage 给出当轮/上句/累计消耗与余量。"
                        + "用量行由宿主注入上下文（SessionGate.injectionLine）——工具面只是查询入口。",
                List.of(
                        new Tool("session_yield", "结束本轮，不触发下一次发送",
                                "session_yield() → {yielded:true, reason}",
                                ps(List.of())),
                        new Tool("session_usage", "当轮消耗 / 上句消耗 / 余量 / 轮次",
                                "session_usage() → {roundTokens, lastUtteranceTokens, totalTokens,"
                                        + " turns, remaining, injection}",
                                ps(List.of()))));
    }

    // ------------------------------------------------------------- 记忆

    /** 记忆（分析 §三.二）：分级读取 / 默认加载 / 纠错 / 完整性修复。 */
    public static ToolGroupProvider memory() {
        return provider("memory", "builtin/memory", Availability.ALWAYS_ON,
                "记忆（不可禁用）：分级读取、纠错、压缩链完整性修复",
                "原始记忆必须调用过才产生——空档是常态，不补、不报缺口、不参与紧邻取值。"
                        + "周期平级五层（day/week/month/quarter/year，ISO 周跨月不嵌套）。"
                        + "纠错与删除是两段式 confirm_token：首段只给预览与令牌，带令牌二次调用才生效"
                        + "（发给模型自身的确认，非外部审批）。",
                List.of(
                        new Tool("memory_read", "按层级读取记忆（含轮内次路由）",
                                "memory_read(level, period, [sub], [all]) → {level, key, content, truncated}",
                                ps(List.of("level", "period"),
                                        "level", "day | week | month | quarter | year | round",
                                        "period", "期号：day=2026-10-23 · week=2026-W43 · month=2026-10"
                                                + " · quarter=2026-Q4 · year=2026 · round=轮档名",
                                        "sub", "次路由：轮内条目序号（level=round 时定位单次思考/调用）",
                                        "all", "true=读整轮（level=round）")),
                        new Tool("memory_default", "默认分级加载（年档完整 + 紧邻已完成天档 + 未吸收）",
                                "memory_default() → {years, nearest_day, unabsorbed}",
                                ps(List.of())),
                        new Tool("memory_correct", "纠错条目（两段式）：先拿令牌，再带令牌落盘",
                                "memory_correct(action=add|remove, target_ref|id, note, confirm_token)",
                                ps(List.of("action"),
                                        "action", "add=添加纠错 · remove=移除纠错",
                                        "target_ref", "add 必填：被纠错的记忆引用（如 day:2026-10-22）",
                                        "id", "remove 必填：纠错条目编号",
                                        "note", "add 必填：纠错说明（读取对应记忆时自动注入）",
                                        "confirm_token", "首段返回的令牌；不带=只预览不落盘")),
                        new Tool("memory_repair", "压缩链完整性修复（只补\"原始在、压缩缺\"的段）",
                                "memory_repair() → {repaired, remaining}",
                                ps(List.of()))));
    }

    // ------------------------------------------------------------- 任务清单

    /** 任务清单（分析 §三.三）。 */
    public static ToolGroupProvider todo() {
        return provider("todo", "builtin/todo", Availability.NORMAL,
                "任务清单：条目/子条目、状态推进（PENDING→DONE|BLOCKED|FAILED）",
                "子条目只支持一层（parentId 一层）；父删=标记+子随之标记（不物理删），"
                        + "purge 才级联物理删——与记忆的\"不轻删\"一致。",
                List.of(
                        new Tool("todo_add", "添加条目", "todo_add(text) → {id, text, status}",
                                ps(List.of("text"), "text", "条目内容")),
                        new Tool("todo_add_sub", "添加子条目（一层）",
                                "todo_add_sub(parent_id, text) → {id, parentId, text}",
                                ps(List.of("parent_id", "text"),
                                        "parent_id", "父条目编号",
                                        "text", "子条目内容")),
                        new Tool("todo_mark", "推进状态",
                                "todo_mark(id, status=PENDING|DONE|BLOCKED|FAILED) → {id, status}",
                                ps(List.of("id", "status"),
                                        "id", "条目编号",
                                        "status", "PENDING | DONE | BLOCKED | FAILED")),
                        new Tool("todo_list", "列出全部条目（含状态与时间戳）",
                                "todo_list() → [{id, text, status, parentId, createdTs, updatedTs}]",
                                ps(List.of())),
                        new Tool("todo_delete", "标记删除（父删级联标记子，不物理删）",
                                "todo_delete(id) → {id, marked}",
                                ps(List.of("id"), "id", "条目编号")),
                        new Tool("todo_purge", "物理删除（级联子条目，不可逆）",
                                "todo_purge(id) → {id, purged}",
                                ps(List.of("id"), "id", "条目编号"))));
    }

    // ------------------------------------------------------------- 笔记

    /** 笔记（分析 §三.四）：限定在 ID 家的 notes/ 下；删除走回收站 + 两段式。 */
    public static ToolGroupProvider notes() {
        return provider("notes", "builtin/notes", Availability.NORMAL,
                "笔记：文件夹/创建/追加/读取/搜索/删除（回收站式，两段式确认）",
                "笔记限定在 ids/<ID>/notes/ 下；创建覆盖是破坏性操作、追加是安全操作，两者分开。"
                        + "删除移入 notes/.trash/<时间戳>/ 而不是抹掉。"
                        + "笔记**不进**记忆的被动检索——要查用 note_search（分析 §三.四 设计决定）。",
                List.of(
                        new Tool("note_mkdir", "创建文件夹", "note_mkdir(path) → {path}",
                                ps(List.of("path"), "path", "相对 notes/ 的路径")),
                        new Tool("note_create", "创建/覆盖笔记（破坏性）",
                                "note_create(path, content) → {path, bytes}",
                                ps(List.of("path", "content"),
                                        "path", "相对 notes/ 的路径（含文件名）",
                                        "content", "全文内容（覆盖已有文件）")),
                        new Tool("note_append", "追加内容（安全操作）",
                                "note_append(path, content) → {path, bytes}",
                                ps(List.of("path", "content"),
                                        "path", "相对 notes/ 的路径",
                                        "content", "追加的文本")),
                        new Tool("note_read", "读取笔记", "note_read(path) → {path, content}",
                                ps(List.of("path"), "path", "相对 notes/ 的路径")),
                        new Tool("note_search", "关键词搜索笔记（主动查，不参与记忆注入）",
                                "note_search(keyword) → [{path, hits}]",
                                ps(List.of("keyword"), "keyword", "关键词")),
                        new Tool("note_delete", "删除笔记（两段式 → 回收站）",
                                "note_delete(path, confirm_token) → {confirm_token} | {deleted, moved_to}",
                                ps(List.of("path"),
                                        "path", "相对 notes/ 的路径",
                                        "confirm_token", "首段返回的令牌；不带=只预览不删除"))));
    }

    // ------------------------------------------------------------- 内部

    private static ToolGroupProvider provider(String group, String category, Availability availability,
                                             String summary, String details, List<Tool> tools) {
        return new ToolGroupProvider.Simple(Version.CORE_API, SOURCE_ID) {
            @Override
            public Descriptor describe() {
                return new Descriptor(group, category, summary, details, availability, tools);
            }
        };
    }

    /**
     * 参数 schema（无参也返回非空 Map——注册表要求 params 非 null，
     * 空 Map 会被判"缺详解"）。
     */
    private static Map<String, Object> ps(List<String> required, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("required", required);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    /** 组名 → 该组是否 ALWAYS_ON（权限文件默认放行判据）。 */
    public static boolean alwaysOn(String group) {
        return "session".equals(group) || "memory".equals(group);
    }

    private BuiltinGroups() {
    }
}
