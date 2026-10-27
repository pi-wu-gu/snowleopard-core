package snowleopard.core.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具工作集（2026-10-27 15:40 (UTC+8)）：<b>模型主动取用 / 释放工具组的运行态</b>。
 *
 * <p>为什么住在 core：身份只有 core 有（{@code Ids.Caller}，由 {@code forCaller} 注入、不可伪造）。
 * 工作集若放宿主，宿主就得自己造一套"这是谁"的判定——与"caller_id 永远由 core 注入"直接冲突。
 *
 * <p>三条不变式（与星期五 SchemaTrimmer / ToolExposure 同源，措辞保持一致）：
 * <ol>
 *   <li><b>元工具不可裁</b>：目录组 {@link #CATALOG_GROUP} 恒为 active——它是拿回别的组唯一入口，
 *       裁掉就是死锁（模型既看不到它，也没有别的手段要求恢复）；</li>
 *   <li><b>解锁可回</b>：{@code tool_help} 之后该组/该工具立刻记入工作集，宿主下一轮声明时才带签名；</li>
 *   <li><b>裁剪必须可见</b>：不在工作集的组仍出现在注入清单里（见 {@link ToolRegistry#injectionBlock()}），
 *       模型据此知道"有这些能力，只是没给签名"，而不是以为它们不存在。</li>
 * </ol>
 *
 * <p><b>不是权限</b>：启用/禁用是 {@link ToolRegistry#isEnabled}（availability 三档 + 权限文件），
 * 属"能不能用"；工作集是"这一轮要不要把签名发给模型"，属"省不省上下文"。两者混用会让会话状态污染权限判定。
 *
 * <p><b>会话边界由宿主定义</b>：core 不推断会话何时结束，宿主在每次会话开始时调 {@link #clear(String)}。
 */
public final class ToolExposure {

    /** 目录组全名：恒在（元工具不可裁）。写死而不是查注册表——清单被改坏也不能连锁失明。 */
    public static final String CATALOG_GROUP = "builtin:core:tool";

    /** 组粒度取用：caller → 组全名集合。 */
    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();

    /** 工具粒度取用（组内筛选）：caller → {@code 组全名.工具名} 集合。 */
    private final Map<String, Set<String>> tools = new ConcurrentHashMap<>();

    /** 已提示过"这是无组工具"的工具键：会话内只提示一次（避免每轮噪音）。 */
    private final Map<String, Set<String>> soloNotified = new ConcurrentHashMap<>();

    private static Set<String> bucket(Map<String, Set<String>> m, String callerId) {
        return m.computeIfAbsent(callerId, k -> ConcurrentHashMap.newKeySet());
    }

    /** 取用整组（下一轮起该组全部工具带签名）。 */
    public void pinGroup(String callerId, String groupFull) {
        require(callerId, groupFull);
        bucket(groups, callerId).add(groupFull);
    }

    /** 只取用组内某个工具（"按组内目录筛选"）。 */
    public void pinTool(String callerId, String groupFull, String toolName) {
        require(callerId, groupFull);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        bucket(tools, callerId).add(groupFull + "." + toolName);
    }

    /**
     * 释放整组：组取用与该组下所有单工具取用一并清掉。
     *
     * @return 是否真的移除了什么（没取用过 → false，调用方据此报"本来就没取用"）
     */
    public boolean unpinGroup(String callerId, String groupFull) {
        if (callerId == null || groupFull == null) {
            return false;
        }
        boolean removed = false;
        Set<String> g = groups.get(callerId);
        if (g != null && g.remove(groupFull)) {
            removed = true;
        }
        Set<String> t = tools.get(callerId);
        if (t != null) {
            String prefix = groupFull + ".";
            removed |= t.removeIf(k -> k.startsWith(prefix));
        }
        return removed;
    }

    /** 释放组内单个工具。 */
    public boolean unpinTool(String callerId, String groupFull, String toolName) {
        if (callerId == null || groupFull == null || toolName == null) {
            return false;
        }
        Set<String> t = tools.get(callerId);
        return t != null && t.remove(groupFull + "." + toolName);
    }

    /** 该组是否在工作集里（整组取用，或至少取用了其中一个工具）。目录组恒 true。 */
    public boolean isActive(String callerId, String groupFull) {
        if (CATALOG_GROUP.equals(groupFull)) {
            return true;
        }
        if (callerId == null || groupFull == null) {
            return false;
        }
        Set<String> g = groups.get(callerId);
        if (g != null && g.contains(groupFull)) {
            return true;
        }
        Set<String> t = tools.get(callerId);
        if (t == null) {
            return false;
        }
        String prefix = groupFull + ".";
        for (String k : t) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 具体某个工具是否该进本轮签名：整组取用 → 全给；否则只给显式取用的那些。 */
    public boolean allows(String callerId, String groupFull, String toolName) {
        if (CATALOG_GROUP.equals(groupFull)) {
            return true;
        }
        if (!isActive(callerId, groupFull)) {
            return false;
        }
        Set<String> g = groups.get(callerId);
        if (g != null && g.contains(groupFull)) {
            return true;
        }
        Set<String> t = tools.get(callerId);
        return t != null && t.contains(groupFull + "." + toolName);
    }

    /** 当前整组取用的组（不含目录组）。 */
    public Set<String> pinnedGroups(String callerId) {
        Set<String> g = groups.get(callerId);
        return g == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(g));
    }

    /** 当前单工具取用的键（{@code 组.工具}）。 */
    public Set<String> pinnedTools(String callerId) {
        Set<String> t = tools.get(callerId);
        return t == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(t));
    }

    /** 会话开始由宿主调用：新会话来过（不保留上一轮任务的长尾）。 */
    public void clear(String callerId) {
        if (callerId == null) {
            return;
        }
        groups.remove(callerId);
        tools.remove(callerId);
        soloNotified.remove(callerId);
    }

    /**
     * 无组工具的一次性提示记账（2026-10-27 裁决）：**每个工具每会话只提示一次**。
     *
     * <p>无组工具是稳定性兜底而非常规形态——不说，模型会以为"这组只有一个工具"；
     * 每轮都说，就变成噪音（模型会把固定提示当背景）。
     *
     * @return {@code true} = 本次是第一次，宿主应在结果末尾附一行"该工具无组"的说明
     */
    public boolean markSoloNotified(String callerId, String toolKey) {
        if (callerId == null || toolKey == null || toolKey.isBlank()) {
            return false;
        }
        return bucket(soloNotified, callerId).add(toolKey);
    }

    /** 工作集签名（宿主可据此判断"这一轮的暴露集合变了没有"）。 */
    public String signature(String callerId) {
        StringBuilder b = new StringBuilder();
        for (String g : pinnedGroups(callerId)) {
            b.append(g).append("*,");
        }
        for (String t : pinnedTools(callerId)) {
            b.append(t).append(',');
        }
        return b.toString();
    }

    private static void require(String callerId, String groupFull) {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("caller_id 必填（core 注入，模型不可伪造）");
        }
        if (groupFull == null || groupFull.isBlank()) {
            throw new IllegalArgumentException("组全名必填");
        }
    }
}
