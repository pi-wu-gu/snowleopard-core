package snowleopard.core.names;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 限定名与重名消歧（M1，方案 D：限定名 + 显式消歧，分析 §2.1）。
 *
 * <p>全名 {@code <来源类型>:<来源ID>:<组名>} 是唯一权威标识——存储、权限、审计一律用全名，
 * 重名问题在存储层不存在。短名供模型与配置使用：唯一时直接解析；冲突时
 * <b>不猜、不改名</b>，返回候选全名；别名（住权限文件，随 ID）显式消歧。
 *
 * <p>为什么不用自动改名：ID 与文件夹绑定，改名会让产物路径漂移（分析 §2.1 方案 B 的致命伤）。
 */
public final class Names {

    /** 来源类型（全名第一段）。 */
    public enum SourceType {
        BUILTIN, LOCAL, MOD;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static SourceType parse(String s) {
            return switch (s) {
                case "builtin" -> BUILTIN;
                case "local" -> LOCAL;
                case "mod" -> MOD;
                default -> throw new IllegalArgumentException(
                        "未知来源类型: " + s + "（可选 builtin | local | mod）");
            };
        }
    }

    /** 全名：来源类型:来源ID:组名。来源 ID 规则见分析 §2.1（local 用 manifest 稳定 gid，路径不进 ID）。 */
    public record Qualified(SourceType type, String sourceId, String group) {

        public Qualified {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("来源 ID 不能为空");
            }
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("组名不能为空");
            }
            if (sourceId.indexOf(':') >= 0 || group.indexOf(':') >= 0) {
                throw new IllegalArgumentException("来源 ID 与组名不得含 ':'：" + sourceId + "/" + group);
            }
        }

        public String full() {
            return type.wire() + ":" + sourceId + ":" + group;
        }

        public static Qualified parse(String full) {
            String[] p = full.split(":", -1);
            if (p.length != 3) {
                throw new IllegalArgumentException("全名必须是 <来源类型>:<来源ID>:<组名>: " + full);
            }
            return new Qualified(SourceType.parse(p[0]), p[1], p[2]);
        }
    }

    /** 注册结果：同全名重名的后者拒绝（分析 §2.1 规则 4——同源重复注册是打包错误）。 */
    public record RegisterResult(boolean accepted, String full, String rejectedBecause) {
    }

    private final Map<String, Qualified> byFull = new LinkedHashMap<>();
    private final List<String> rejected = new ArrayList<>();

    /** 注册工具组。同全名第二次起拒绝并留痕（不覆盖、不改名）。 */
    public RegisterResult register(Qualified q) {
        String full = q.full();
        if (byFull.containsKey(full)) {
            rejected.add(full);
            return new RegisterResult(false, full, "同全名重复注册（先到者保留）");
        }
        byFull.put(full, q);
        return new RegisterResult(true, full, null);
    }

    /**
     * 注销（M6：模组卸载）。返回是否真删了——没有就是幂等成功。
     * 注销后短名即释放，同全名可重新注册（卸载重装是正常生命周期）。
     */
    public boolean unregister(String full) {
        return byFull.remove(full) != null;
    }

    public Set<String> fullNames() {
        return java.util.Collections.unmodifiableSet(byFull.keySet());
    }

    /**
     * 歧义短名表（M7 启动报告的"重名消歧表"）：短名 → 候选全名（≥2 才算歧义）。
     * 歧义该被显式暴露而不是等模型踩（分析 §2.1 规则 5）。
     */
    public java.util.Map<String, List<String>> ambiguous() {
        java.util.Map<String, List<String>> byShort = new LinkedHashMap<>();
        for (String full : byFull.keySet()) {
            byShort.computeIfAbsent(full.substring(full.lastIndexOf(':') + 1),
                    k -> new ArrayList<>()).add(full);
        }
        java.util.Map<String, List<String>> out = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<String>> e : byShort.entrySet()) {
            if (e.getValue().size() >= 2) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return out;
    }

    public List<String> rejected() {
        return java.util.Collections.unmodifiableList(rejected);
    }

    /**
     * 短名解析（三态，不猜）：唯一 → {@link Resolution.Found}；冲突 → {@link Ambiguous}（带候选）；
     * 没有 → {@link Resolution.NotFound}。
     *
     * <p><b>API note</b>：返回 {@link Resolution} sealed 三态（2026-10-23 01:50 (UTC+8) 波次 4——
     * 原返回 Object（String | Ambiguous | null），分支藏在 instanceof 与 null 判断里；
     * 形状即文档，三分支必须被调用方看见）。行为未变。
     *
     * @param aliases 权限文件的 {@code aliases}（随 ID）；别名指向不存在的全名同样 Ambiguous 拒绝
     */
    public Resolution resolve(String shortName, Map<String, String> aliases) {
        String aliased = aliases == null ? null : aliases.get(shortName);
        if (aliased != null) {
            if (byFull.containsKey(aliased)) {
                return new Resolution.Found(aliased);
            }
            return new Ambiguous(shortName, List.of(aliased), "别名指向不存在的全名");
        }
        List<String> hits = new ArrayList<>();
        for (String full : byFull.keySet()) {
            if (full.endsWith(":" + shortName) || full.equals(shortName)) {
                hits.add(full);
            }
        }
        if (hits.size() == 1) {
            return new Resolution.Found(hits.get(0));
        }
        if (hits.isEmpty()) {
            return new Resolution.NotFound(shortName);
        }
        return new Ambiguous(shortName, hits, "短名有多个来源");
    }

    /**
     * 短名解析三态：{@code Found}（唯一命中）| {@code Ambiguous}（歧义带候选）| {@code NotFound}。
     * 返回形状即文档——调用方的 switch 覆盖了全部可能，没有第四种"没想到"。
     */
    public sealed interface Resolution permits Resolution.Found, Ambiguous, Resolution.NotFound {

        /** 唯一命中：限定全名。 */
        record Found(String full) implements Resolution {
        }

        /** 无命中：短名未注册或已注销。 */
        record NotFound(String shortName) implements Resolution {
        }
    }

    /** 短名歧义：携带候选全名，模型须改用全名或在权限文件配别名。 */
    public record Ambiguous(String shortName, List<String> candidates, String why) implements Resolution {

        public String message() {
            return "「" + shortName + "」有 " + candidates.size() + " 个来源：" + candidates
                    + "，请用全名或在权限文件里配置别名（" + why + "）";
        }
    }

    /**
     * 校验别名表（配置错误即拒该条，不锁死启动）。
     *
     * <p>规则（2026-10-22 22:27 复核后定）：
     * <ul>
     *   <li>别名键是<b>歧义短名</b> → 允许——这正是"显式消歧"（方案 D 规则 3 的本意）；
     *       启动报告的消歧表会让它可见；</li>
     *   <li>别名键是<b>唯一短名</b> → 拒（遮蔽了本来明确的名字，纯属添乱）；</li>
     *   <li>两别名同键不同指向 → 拒；指向不存在的全名 → 拒。</li>
     * </ul>
     */
    public static List<String> validateAliases(Map<String, String> aliases, Names names) {
        List<String> errors = new ArrayList<>();
        if (aliases == null || aliases.isEmpty()) {
            return errors;
        }
        Map<String, Integer> shortCount = new LinkedHashMap<>();
        for (String full : names.fullNames()) {
            String sh = full.substring(full.lastIndexOf(':') + 1);
            shortCount.merge(sh, 1, Integer::sum);
        }
        Map<String, String> seen = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isBlank() || v == null || v.isBlank()) {
                errors.add("别名键/值不能为空: " + k + " → " + v);
                continue;
            }
            String prev = seen.put(k, v);
            if (prev != null && !prev.equals(v)) {
                errors.add("别名冲突: 「" + k + "」同时指向 " + prev + " 与 " + v);
            }
            Integer cnt = shortCount.get(k);
            if (cnt != null && cnt == 1) {
                errors.add("别名「" + k + "」遮蔽了唯一短名——唯一的名字不需要别名");
            }
            if (!names.fullNames().contains(v)) {
                errors.add("别名「" + k + "」指向不存在的全名: " + v);
            }
        }
        return errors;
    }

    private Names() {
    }

    public static Names empty() {
        return new Names();
    }

    /** 便捷：从全名集合装载（顺序即注册序）。 */
    public static Names of(Collection<String> fulls) {
        Names n = new Names();
        for (String f : fulls) {
            n.register(Qualified.parse(f));
        }
        return n;
    }
}
