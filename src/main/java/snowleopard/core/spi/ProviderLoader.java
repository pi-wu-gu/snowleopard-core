package snowleopard.core.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import snowleopard.core.Version;
import snowleopard.core.names.Names;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.report.Issue;

/**
 * Provider 装载器（M6，2026-10-22 23:36 (UTC+8)）。
 *
 * <p>三件事：① <b>装载校验</b>（{@code coreApi} 不匹配 / 来源空 → 拒载留 ERROR）；
 * ② <b>来源强制</b>（限定名由本类按 {@code <类型>:<sourceId>:<组名>} 拼，provider 造不了）；
 * ③ <b>按来源注销</b>（模组卸载——开发流程 M6："模组卸载要注销其工具组"，
 * 否则残影留在注册表里，搜索还能搜到已经不存在的模组的能力）。
 *
 * <p>发现走 {@link ServiceLoader}（{@code META-INF/services/…ToolGroupProvider}）——
 * mod-shell 把模组的 Provider 收上来后也走 {@link #load}，core 不知道什么是模组。
 */
public final class ProviderLoader {

    private final ToolRegistry registry;
    /** 已装载的 provider（按全名，注销时用）。 */
    private final Map<String, ToolGroupProvider> loaded = new LinkedHashMap<>();

    public ProviderLoader(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 装载一个 provider。校验顺序（顺序即语义）：
     * coreApi 匹配 → 来源非空 → 注册进注册表（注册表自己再做清单校验与重名判定）。
     *
     * @return 全名（成功）或 null（拒载；详情在 {@link #issues()}）
     */
    public String load(ToolGroupProvider p, Names.SourceType type) {
        if (p.coreApi() != Version.CORE_API) {
            issues.add(Issue.error(p.sourceId() + ":" + safeGroup(p),
                    "拒载: core_api 不匹配（provider 要求 " + p.coreApi()
                            + "，当前 " + Version.CORE_API + "）——契约版本不符，宁可拒也不静默半接入"));
            return null;
        }
        if (p.sourceId() == null || p.sourceId().isBlank()) {
            issues.add(Issue.error("?:?", "拒载: 来源 ID 为空——工具组必须标注来源（modid / gid）"));
            return null;
        }
        ToolGroupProvider.Descriptor d = p.describe();
        Names.Qualified q = new Names.Qualified(type, p.sourceId(), d.group());
        ToolRegistry.Group g = new ToolRegistry.Group(q, d.category(), d.summary(),
                d.details(), d.availability(), d.tools());
        if (!registry.register(g)) {
            // 注册表已留详细 ERROR（重名/清单不合格）
            issues.addAll(registry.issues());
            return null;
        }
        loaded.put(g.full(), p);
        return g.full();
    }

    /** 批量装载（部分失败不锁死其余——与注册表"坏清单不锁死整批"同原则）。 */
    public List<String> loadAll(Collection<ToolGroupProvider> ps, Names.SourceType type) {
        List<String> ok = new ArrayList<>();
        for (ToolGroupProvider p : ps) {
            String full = load(p, type);
            if (full != null) {
                ok.add(full);
            }
        }
        return ok;
    }

    /** ServiceLoader 发现并装载（mod-shell / 外部目录的入口）。 */
    public List<String> loadDiscovered(Names.SourceType type, ClassLoader cl) {
        List<ToolGroupProvider> found = new ArrayList<>();
        for (ToolGroupProvider p : ServiceLoader.load(ToolGroupProvider.class, cl)) {
            found.add(p);
        }
        return loadAll(found, type);
    }

    /**
     * 按来源注销（模组卸载）。删掉该来源的全部组、释放短名、清 provider 引用。
     * 幂等：来源不存在也返回空清单（卸载重卸是正常生命周期）。
     */
    public List<String> unload(Names.SourceType type, String sourceId) {
        List<String> removed = new ArrayList<>(registry.unregisterSource(type, sourceId));
        for (String full : removed) {
            loaded.remove(full);
            // 卸载记 WARN（M7 验收："WARN 汇总进报告"）——模组走了不是静默消失。
            // 已在执行的调用允许完成（不回溯，§4.3）：注销只影响注册表。
            issues.add(Issue.warn(full, "来源 " + type.wire() + ":" + sourceId
                    + " 注销（模组卸载/移除）——该组不再可用；在执行的调用允许完成"));
        }
        return removed;
    }

    /** 本装载器的诊断（拒载原因）。 */
    public List<Issue> issues() {
        return List.copyOf(issues);
    }

    /** 当前装载的全名（诊断）。 */
    public Collection<String> loadedFullNames() {
        return List.copyOf(loaded.keySet());
    }

    private final List<Issue> issues = new ArrayList<>();

    private static String safeGroup(ToolGroupProvider p) {
        try {
            return p.describe().group();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
