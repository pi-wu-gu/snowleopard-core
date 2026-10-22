package snowleopard.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import snowleopard.core.json.Json;
import snowleopard.core.report.Issue;

/**
 * 工具配置：两级来源、单文件全覆盖、三种语义（M1，分析 §九.2）。
 *
 * <p>两级来源（低→高）：① ID 自带 {@code ids/<ID>/tools-default.json}（"无外部设置即使用其自身默认设置"）
 * ② 外部 {@code tool-configs/} 下启动时引用的文件，覆盖 ①。
 * <b>单文件配置全部工具</b>：没写到的工具 = 默认设置（静默 OK）。
 *
 * <p>三种语义（草案原文）：没写=默认（OK）；<b>匹配失败=WARN</b>（配置跨档共用是常态，继续跑）；
 * <b>参数错误=ERROR</b>（该项拒绝生效、退回默认，不许"半生效"）。
 *
 * <p>开关归权限文件、参数归本文件（职责单点收口，§10.6-10）；
 * 键用<b>全名</b>（与重名方案 D 一致；写短名命中歧义时同样按匹配失败 WARN）。
 */
public final class ToolConfig {

    /** 装载结果：生效值（全名 → 参数表）+ 问题清单 + 每个键的生效来源（进启动报告）。 */
    public record Loaded(Map<String, Map<String, Object>> effective,
                         List<Issue> issues,
                         Map<String, String> sourceOf) {
    }

    private static final Set<String> SWITCH_KEYS =
            Set.of("enabled", "disabled", "enable", "disable", "switch");

    private ToolConfig() {
    }

    /** 装载 ID 自带默认（①）。 */
    public static Loaded loadIdDefault(Path toolsDefaultJson, Set<String> knownFulls) {
        Map<String, Object> raw = readJson(toolsDefaultJson);
        if (raw == null) {
            return new Loaded(Map.of(), List.of(), Map.of());
        }
        return load(raw, knownFulls, "id-default");
    }

    /** 两级合并装载：外部配置（②）覆盖 ID 自带（①），没写的键回落。 */
    public static Loaded load(Path toolsDefaultJson, Path externalJson, Set<String> knownFulls) {
        Loaded base = loadIdDefault(toolsDefaultJson, knownFulls);
        Map<String, Object> ext = readJson(externalJson);
        if (ext == null) {
            return base;
        }
        Loaded over = load(ext, knownFulls, "external");

        Map<String, Map<String, Object>> eff = new LinkedHashMap<>(base.effective());
        eff.putAll(over.effective()); // 整键覆盖：外部文件写了该工具就整体接管
        List<Issue> issues = new ArrayList<>(base.issues());
        issues.addAll(over.issues());
        Map<String, String> src = new LinkedHashMap<>(base.sourceOf());
        src.putAll(over.sourceOf());
        return new Loaded(eff, issues, src);
    }

    /** 单文件装载：解析三种语义。 */
    public static Loaded load(Map<String, Object> raw, Set<String> knownFulls, String sourceTag) {
        Map<String, Map<String, Object>> eff = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        Map<String, String> src = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();

            // 匹配失败 = WARN：配置跨档共用（模组没装/改名）是常态，不锁死启动
            if (!knownFulls.contains(key)) {
                issues.add(Issue.warn(key, "匹配失败（" + sourceTag + "）：没有匹配到任何工具组"));
                continue;
            }
            // 参数错误 = ERROR：值形态不对 → 该项拒绝生效、退回默认
            if (!(val instanceof Map<?, ?> m)) {
                issues.add(Issue.error(key, "参数错误（" + sourceTag + "）：工具配置必须是对象，实际 "
                        + typeName(val) + "——该项拒绝生效，使用默认"));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) m;
            List<String> switchHit = new ArrayList<>();
            for (String pk : params.keySet()) {
                if (SWITCH_KEYS.contains(pk.toLowerCase(java.util.Locale.ROOT))) {
                    switchHit.add(pk);
                }
            }
            if (!switchHit.isEmpty()) {
                // 开关归权限文件：工具配置里出现开关 → WARN 并剔除（不生效）
                issues.add(Issue.warn(key, "「" + switchHit + "」是工具开关，归权限文件管理——"
                        + "此处忽略（§九.2 职责切分）"));
                params = new LinkedHashMap<>(params);
                switchHit.forEach(params::remove);
            }
            eff.put(key, params);
            src.put(key, sourceTag);
        }
        return new Loaded(eff, issues, src);
    }

    private static Map<String, Object> readJson(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return null;
        }
        try {
            return Json.parseObject(Files.readString(p));
        } catch (IOException | IllegalArgumentException e) {
            // 文件读不了/不是合法 JSON：整份按匹配失败处理（WARN），不锁死启动
            throw new IllegalStateException("配置文件不可读: " + p + " —— " + e.getMessage(), e);
        }
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
