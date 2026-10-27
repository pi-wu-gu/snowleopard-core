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

    /** 工具目录组短名（第 7 组）：逐级查询与工作集的唯一入口，恒在（元工具不可裁）。 */
    public static final String CATALOG_GROUP = "tool";

    /**
     * 七组短名（顺序即装载顺序）：**工具目录在最前**（模型先看到"怎么查"，再看到有哪些组），
     * 其后会话 → 记忆 → 任务 → 笔记 → 唤醒 → 群组。
     */
    public static final List<String> GROUP_NAMES = List.of("tool", "session", "memory", "todo", "notes",
            "wake", "group");

    /** 七组 provider（每次调用返回新实例——provider 无状态，注册表持有描述即可）。 */
    public static List<ToolGroupProvider> all() {
        return List.of(tool(), session(), memory(), todo(), notes(), wake(), group());
    }

    // ------------------------------------------------------------- 工具目录

    /**
     * 工具目录（第 7 组，2026-10-27 16:00 (UTC+8)）：**逐级查询的唯一入口**。
     *
     * <p>为什么必须存在：本轮只注入组清单，组内工具的用法按需查——没有这三个工具，
     * "组内工具信息不注入"就等于让模型猜（全量平铺的老路）。ALWAYS_ON：目录被禁用 = 模型彻底失明。
     */
    public static ToolGroupProvider tool() {
        return provider(CATALOG_GROUP, "builtin/tool", Availability.ALWAYS_ON,
                "工具目录（不可禁用）：列组、查组内工具、增删工作集",
                "tool_help / tool_scope 的 target 可写组短名或全名，多个用逗号分隔（一次最多 4 个）；"
                        + "加入工作集后**下一轮**请求才带签名——本轮别等。",
                List.of(
                        new Tool("tool_list", "列工具组（按来源分节）与无组工具",
                                "tool_list(source?) → 组清单 {全名, 简介, 工具数, 启用态} + 无组工具",
                                ps(List.of(),
                                        "source", "只列某个来源（builtin | snowleopard | mod:snowleopard）")),
                        new Tool("tool_help", "查组详解与成员，或查单个工具的用法；查到即加入工作集",
                                "tool_help(targets) → 组: 详解+成员名单 | 组.工具: 用法+参数；下一轮起可调用",
                                ps(List.of("targets"),
                                        "targets", "组名 或 组.工具，逗号分隔；短名歧义时报候选不猜（≤4 个）")),
                        new Tool("tool_scope", "显式增删工作集（省上下文；可只取组内某个工具）",
                                "tool_scope(action, target) → 当前工作集 {groups, tools}",
                                ps(List.of("action", "target"),
                                        "action", "add | remove",
                                        "target", "组名 或 组.工具，逗号分隔"))));
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

    // ------------------------------------------------------------- 唤醒

    /**
     * 主动唤起（2026-10-23 21:55 (UTC+8)，裁决：深度 1 / 在运行则失败 / 黑名单属被唤起方）。
     */
    public static ToolGroupProvider wake() {
        return provider("wake", "builtin/wake", Availability.NORMAL,
                "主动唤起：请另一个 ID 开工（在运行则失败，不排队）",
                "core 只登记请求与裁决，真唤起由宿主执行（它 poll 待执行请求后启动会话并回写）。"
                        + "六道闸：ID 合法 → 非自唤起 → 非成环 → 深度 ≤ 1 → 目标不在运行 → 未被目标拉黑。"
                        + "唤起不代付预算——被唤起方用自己的预算，否则唤起就是绕过预算的后门。",
                List.of(
                        new Tool("wake", "唤起指定 ID（已在运行则失败）",
                                "wake(model_id, prompt?, reason?, client_req_id?) → {reqId, state:pending}",
                                ps(List.of("model_id"),
                                        "model_id", "目标模型 ID（已登记才有家目录）",
                                        "prompt", "给对方的任务提示（空=日常巡检）",
                                        "reason", "为什么唤起（进审计，对方可见）",
                                        "client_req_id", "幂等键：同键重发 → WARN 不产生第二条")),
                        new Tool("wake_status", "查看一次唤起的执行结果",
                                "wake_status(req_id) → {reqId, state, outcome}",
                                ps(List.of("req_id"), "req_id", "受理时返回的请求编号")),
                        new Tool("wake_deny", "拉黑/取消拉黑（我不接受谁唤起我）",
                                "wake_deny(model_id, on=true) → {deny[]}",
                                ps(List.of("model_id"),
                                        "model_id", "要拉黑的 ID",
                                        "on", "true=加入禁止列表 · false=移除")),
                        new Tool("wake_policy", "我的策略：黑名单 + 最近被唤起记录",
                                "wake_policy() → {deny[], recent[], running, quotaPerHour}",
                                ps(List.of()))));
    }

    // ------------------------------------------------------------- 群组

    /**
     * 本地群组（2026-10-23 21:55 (UTC+8)，三级角色：创建者 > 管理 > 成员；不跨网络）。
     */
    public static ToolGroupProvider group() {
        return provider("group", "builtin/group", Availability.NORMAL,
                "本地群组：建/散/加人/任命管理/发消息/读未读/搜索（不跨网络）",
                "三级角色：创建者（解散、移除、任命管理）＞ 管理（添加 ID）＞ 成员（读发退）。"
                        + "消息 append-only（多 ID 同时写，seq 游标读取）。"
                        + "引用只回状态不回内容：ok / stale（内容已变更）/ missing（源已丢失）——"
                        + "内容仍在发送方家里（核心数据不复用信息）。",
                List.of(
                        new Tool("group_create", "建群（自动分配 gid，建群者即创建者）",
                                "group_create(name) → {gid, name, creator}",
                                ps(List.of("name"), "name", "群组名（可重名，gid 才是标识）")),
                        new Tool("group_disband", "解散群组（仅创建者；移入回收区）",
                                "group_disband(gid) → {gid, disbanded, movedTo}",
                                ps(List.of("gid"), "gid", "群组编号")),
                        new Tool("group_add", "添加 ID（管理 + 创建者；普通成员不可）",
                                "group_add(gid, model_id) → {gid, member, role}",
                                ps(List.of("gid", "model_id"),
                                        "gid", "群组编号",
                                        "model_id", "要加入的 ID（需已登记）")),
                        new Tool("group_remove", "移除 ID（仅创建者）",
                                "group_remove(gid, model_id) → {gid, removed}",
                                ps(List.of("gid", "model_id"),
                                        "gid", "群组编号",
                                        "model_id", "要移除的 ID（管理角色一并清除）")),
                        new Tool("group_grant", "任命/撤销管理（仅创建者）",
                                "group_grant(gid, model_id, role=admin|member) → {gid, member, role}",
                                ps(List.of("gid", "model_id"),
                                        "gid", "群组编号",
                                        "model_id", "目标成员（须已在群里）",
                                        "role", "admin（管理）| member（成员）")),
                        new Tool("group_leave", "自己退出（创建者不可退，先解散）",
                                "group_leave(gid) → {gid, left}",
                                ps(List.of("gid"), "gid", "群组编号")),
                        new Tool("group_send", "发消息（成员及以上；可带引用）",
                                "group_send(gid, text, ref?, client_msg_id?) → {seq, ref:{state}}",
                                ps(List.of("gid", "text"),
                                        "gid", "群组编号",
                                        "text", "正文（≤4096 字符）",
                                        "ref", "引用：note:<相对路径> 或 file:<相对路径>（只能指自己家内）",
                                        "client_msg_id", "幂等键：同键重发 → WARN")),
                        new Tool("group_read", "读消息（成员及以上；默认读未读）",
                                "group_read(gid, limit=20, since_seq?) → {messages[], lastReadSeq}",
                                ps(List.of("gid"),
                                        "gid", "群组编号",
                                        "limit", "条数上限（≤100）",
                                        "since_seq", "起始 seq（省略=从上次已读之后）")),
                        new Tool("group_list", "我的群列表（角色 + 未读数）",
                                "group_list() → [{gid, name, myRole, unread, messages}]",
                                ps(List.of())),
                        new Tool("group_search", "搜索消息（子串 + 时间窗）",
                                "group_search(gid, keyword, since_ts?, until_ts?, limit=20) → {hits[]}",
                                ps(List.of("gid", "keyword"),
                                        "gid", "群组编号",
                                        "keyword", "关键词（大小写不敏感子串）",
                                        "since_ts", "起始时间（epoch 毫秒）",
                                        "until_ts", "结束时间（epoch 毫秒）",
                                        "limit", "条数上限（≤100）"))));
    }

    // ------------------------------------------------------------- 内部

    /**
     * provider 装配：**组详解在装配时生成**（三段模板）。
     *
     * <p>为什么要生成而不是各写一遍：详解必须列出**全部成员与一行说明**（目录），
     * 手写会漏、漏了模型就拼不出可调用路径（"这组管移动" → 猜 `goto` 还是 `navigate`）。
     * 从 {@code tools} 直接渲染，目录永远不会缺，也不会与清单漂移。
     *
     * <p>{@code notes} 是"使用要点"（约束/红线/坑），仍由各组自己写。
     */
    private static ToolGroupProvider provider(String group, String category, Availability availability,
                                             String summary, String notes, List<Tool> tools) {
        String details = renderDetails(summary, notes, tools);
        return new ToolGroupProvider.Simple(Version.CORE_API, SOURCE_ID) {
            @Override
            public Descriptor describe() {
                return new Descriptor(group, category, summary, details, availability, tools);
            }
        };
    }

    /** 三段模板：功能（组简介首行）+ 目录（全部成员，名字 + 一行说明）+ 使用要点。 */
    private static String renderDetails(String summary, String notes, List<Tool> tools) {
        StringBuilder b = new StringBuilder();
        b.append("功能：").append(summary).append('\n');
        b.append("目录：\n");
        for (Tool t : tools) {
            b.append("- ").append(t.name()).append(" — ")
                    .append(t.hasSummary() ? t.summary() : "（无简介）").append('\n');
        }
        if (notes != null && !notes.isBlank()) {
            b.append("使用要点：").append(notes.strip());
        }
        return b.toString();
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

    /** 组名 → 该组是否默认放行（权限文件未提时的降级判据）。目录组同属"关掉就没有能力"的一类。 */
    public static boolean alwaysOn(String group) {
        return CATALOG_GROUP.equals(group) || "session".equals(group) || "memory".equals(group);
    }

    private BuiltinGroups() {
    }
}
