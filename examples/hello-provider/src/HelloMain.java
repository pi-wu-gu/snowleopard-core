import java.nio.file.Path;
import java.util.Map;

import snowleopard.core.SnowLeopard;
import snowleopard.core.names.Names;
import snowleopard.core.result.Result;

/**
 * 最小接入闭环 · <b>标准入口=装配门面</b>（接入指南 · 2026-10-23 01:55 (UTC+8) 波次 5A）。
 *
 * <p>四步：装配（一行）→ 装载（看启动报告）→ 查询（help）→ 调用自带能力（返回 Result）。
 * 手动装配的展开式版本见 {@code HelloManualMain}（常绿保活：门面只调公开 API，两条路径同行为）。
 * 注意分工：<b>core 管注册/门禁/记忆/确认，不执行你的工具</b>——外部工具的执行体在你自己的
 * 代码里，返回值同样用 {@code Result} 四级形状。
 */
public final class HelloMain {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/hello-home");

        // ── 步骤 1：装配（门面一行；权限政策必须显式二选一）──
        SnowLeopard core = SnowLeopard.bootstrap(root, SnowLeopard.Options.allowAll());

        // ── 步骤 2：装载工具组（限定名由装载器拼）──
        String full = core.loader().load(new HelloProvider(), Names.SourceType.LOCAL);
        System.out.println("装载: " + full);                  // → local:hello-demo:echo

        // ── 步骤 3：启动报告 + 查询注入面 ──
        System.out.println("启动报告: " + core.startupReport().get(0));
        System.out.println("help:\n" + core.registry().help("echo", Map.of()));

        // ── 步骤 4：调用自带能力（工具返回值=Result 四级）──
        SnowLeopard.CallerScope scope = core.forCaller("model-01");   // 进入 ID 的家
        Result r = scope.todos().add("读一遍接入指南");
        System.out.println("todo add → " + r.getClass().getSimpleName());   // → Ok
        System.out.println("示例四级: " + Result.warn(java.util.List.of(1), "示例警告").getClass().getSimpleName());
    }

    private HelloMain() {
    }
}
