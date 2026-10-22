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
 * 最小接入闭环（接入指南 · 2026-10-23 01:35 (UTC+8)）—— 完整可运行版。
 *
 * <p>四步：装配 → 装载（看启动报告）→ 查询（help）→ 调用自带能力（返回 Result）。
 * 注意分工：<b>core 管注册/门禁/记忆/确认，不执行你的工具</b>——外部工具的执行体在你自己的
 * 代码里，返回值同样用 {@code Result} 四级形状。
 */
public final class HelloMain {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/hello-home");

        // ── 步骤 1：装配（顺序即依赖，完整清单见接入指南 §二）──
        Ids ids = Ids.at(root);                              // 存储根：全部 ID 家的父目录
        Ids.Caller caller = new Ids.Caller("model-01");      // 调用方身份（core 注入，不可伪造）
        ToolRegistry registry = new ToolRegistry();
        ProviderLoader loader = new ProviderLoader(registry);
        Permissions perms = Permissions.defaultAll(registry); // 注意：全权限默认——生产请 fromFiles

        // ── 步骤 2：装载工具组（限定名由装载器拼）──
        String full = loader.load(new HelloProvider(), Names.SourceType.LOCAL);
        System.out.println("装载: " + full);                  // → local:hello-demo:echo

        // ── 步骤 3：启动报告 + 查询注入面 ──
        List<String> report = StartupReport.render(
                registry, registry.names(), loader.issues(), null, null);
        System.out.println("启动报告: " + report.get(0));
        System.out.println("help:\n" + registry.help("echo", Map.of()));

        // ── 步骤 4：调用自带能力（工具返回值=Result 四级）──
        TodoStore todos = new TodoStore(ids, caller);         // 工作目录自动挂在 caller 的家下
        Result r = todos.add("读一遍接入指南");
        System.out.println("todo add → " + r.getClass().getSimpleName());   // → Ok
        System.out.println("示例四级: " + Result.warn(List.of(1), "示例警告").getClass().getSimpleName());
    }

    private HelloMain() {
    }
}
