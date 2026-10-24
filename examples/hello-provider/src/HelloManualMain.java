import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import snowleopard.core.ids.Ids;
import snowleopard.core.names.Names;
import snowleopard.core.perm.Permissions;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.report.StartupReport;
import snowleopard.core.result.Result;
import snowleopard.core.spi.ProviderLoader;
import snowleopard.core.todo.TodoStore;

/**
 * 最小接入闭环 · <b>手动装配（门面的展开式）</b>（接入指南 · 2026-10-23 01:55 (UTC+8) 波次 5A）。
 *
 * <p>与 {@code HelloMain}（门面版）同行为——本文件是 <b>S1 保活</b>：门面只调公开 API、
 * 无特权路径，手动装配=门面的逐步展开。需要自定义装配（非常规顺序/自管组件）时抄这份。
 * 每次 {@code ./build.sh check examples} 两条路径都跑，行为漂移即 FAIL。
 */
public final class HelloManualMain {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/hello-home");

        // ── 手动装配：顺序即依赖（门面 bootstrap 的展开式）──
        Ids ids = Ids.at(root);
        Ids.Caller caller = new Ids.Caller("model-01");
        ToolRegistry registry = new ToolRegistry();
        ProviderLoader loader = new ProviderLoader(registry);
        Permissions perms = Permissions.defaultAll(registry);

        String full = loader.load(new HelloProvider(), Names.SourceType.LOCAL);
        System.out.println("装载: " + full);

        // 渲染层只收快照（组 → 类型 + 来源 ID）：门面之外没人同时认识注册表与报告
        List<StartupReport.GroupLine> lines = registry.groups().stream()
                .map(g -> new StartupReport.GroupLine(g.id().type(), g.id().sourceId()))
                .toList();
        List<String> report = StartupReport.render(
                lines, registry.names(), loader.issues(), null, null);
        System.out.println("启动报告: " + report.get(0));
        System.out.println("help:\n" + registry.help("echo", Map.of()));

        TodoStore todos = new TodoStore(ids, caller);
        Result r = todos.add("读一遍接入指南");
        System.out.println("todo add → " + r.getClass().getSimpleName());
    }

    private HelloManualMain() {
    }
}
