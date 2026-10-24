package snowleopard.core.report;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.Version;
import snowleopard.core.names.Names;

/**
 * 启动报告（M7，2026-10-22 23:58 (UTC+8)，分析 §2.1 规则 5 / 分析 §二.二.3）。
 *
 * <p>每轮启动输出一次：契约版本、工具组来源分布（builtin/local/mod + modid 列表）、
 * <b>重名消歧表</b>（歧义显式暴露而不是等模型踩）、配置生效来源与失效汇总、
 * <b>WARN/ERROR 汇总</b>（模组卸载、拒载、配置失败都进这里）。
 *
 * <p>只读渲染：不改任何状态；宿主拿到 {@code List<String>} 直接打日志或落盘。
 */
public final class StartupReport {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private StartupReport() {
    }

    /**
     * 已注册工具组的一行快照（只有渲染要用的两样，不携带活对象）。
     *
     * <p>为什么是快照而不是 {@code ToolRegistry} 本身（2026-10-24 06:15 (UTC+8)）：
     * 渲染层认识注册表 = {@code report → registry}，而 registry 记问题要用 {@code report.Issue}，
     * 两包互指成环。环不报错、不失败，只是让"改一处要问另一处"，最后谁都改不动——
     * 所以按"**渲染不要认识活对象**"切断：调用方（门面）负责把组压成快照。
     */
    public record GroupLine(Names.SourceType type, String sourceId) {
    }

    /**
     * 渲染报告。
     *
     * @param groups      已注册组的快照（来源分布、组数）
     * @param names       名字表（重名消歧）
     * @param issues      全部装载/卸载/配置问题（WARN 与 ERROR 都汇总）
     * @param configSrc   生效配置来源描述（如 {@code id 自带 tools-default.json ← tool-configs/x.json}），可空
     * @param extraNotes  宿主附加行（如模组清单来源说明），可空
     */
    public static List<String> render(List<GroupLine> groups, Names names,
                                      List<Issue> issues, String configSrc,
                                      List<String> extraNotes) {
        List<String> lines = new ArrayList<>();
        lines.add("启动报告 · " + OffsetDateTime.now(ZoneOffset.ofHours(8)).format(TS) + " (UTC+8)");
        lines.add("契约: core_api=" + Version.CORE_API + " · 发布版本 " + Version.release());

        // 来源分布（分析 §二.二.3：标注来源 modid）
        Map<Names.SourceType, List<String>> byType = new LinkedHashMap<>();
        for (GroupLine g : groups) {
            byType.computeIfAbsent(g.type(), k -> new ArrayList<>()).add(g.sourceId());
        }
        StringBuilder dist = new StringBuilder("工具组 ").append(groups.size()).append(" 个（");
        boolean first = true;
        for (Names.SourceType t : Names.SourceType.values()) {
            List<String> ids = byType.getOrDefault(t, List.of());
            if (!first && !ids.isEmpty()) {
                dist.append(" · ");
            }
            if (!ids.isEmpty()) {
                first = false;
                dist.append(t.wire()).append(' ').append(ids.size());
                if (t == Names.SourceType.MOD) {
                    dist.append(" [").append(String.join(", ", ids.stream().distinct().toList()))
                            .append(']');
                }
            }
        }
        if (first) {
            dist.append("空");
        }
        dist.append(')');
        lines.add(dist.toString());

        // 重名消歧表（分析 §2.1 规则 5）
        Map<String, List<String>> amb = names.ambiguous();
        if (amb.isEmpty()) {
            lines.add("重名消歧表: （无歧义）");
        } else {
            lines.add("重名消歧表: " + amb.size() + " 个歧义短名（用别名定向或全名调用）");
            for (Map.Entry<String, List<String>> e : amb.entrySet()) {
                lines.add("  " + e.getKey() + " → " + e.getValue());
            }
        }

        // 配置来源
        lines.add("配置: " + (configSrc == null || configSrc.isBlank()
                ? "内置默认（未加载外部配置）" : configSrc));

        // WARN/ERROR 汇总（模组卸载、拒载、配置失败都在）
        List<Issue> warns = issues.stream().filter(i -> i.level() == Issue.Level.WARN).toList();
        List<Issue> errs = issues.stream().filter(i -> i.level() == Issue.Level.ERROR).toList();
        if (warns.isEmpty() && errs.isEmpty()) {
            lines.add("问题汇总: （无）");
        } else {
            lines.add("问题汇总: 警告 " + warns.size() + " · 错误 " + errs.size());
            for (Issue i : warns) {
                lines.add("  [WARN] " + i.where() + ": " + i.message());
            }
            for (Issue i : errs) {
                lines.add("  [ERROR] " + i.where() + ": " + i.message());
            }
        }

        if (extraNotes != null) {
            for (String n : extraNotes) {
                lines.add(n);
            }
        }
        return lines;
    }
}
