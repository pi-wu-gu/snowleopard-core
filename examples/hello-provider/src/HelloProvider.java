import java.util.List;
import java.util.Map;

import snowleopard.core.Version;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.spi.ToolGroupProvider;

/**
 * 示例外部工具组（接入指南 · 2026-10-23 01:35 (UTC+8)）。
 *
 * <p>接入者只做三件事：给契约号、给来源 ID、描述工具组。<b>限定名不由你拼</b>——
 * 装载器按 {@code <来源类型>:<来源ID>:<组名>} 拼出 {@code local:hello-demo:echo}。
 */
public final class HelloProvider extends ToolGroupProvider.Simple {

    public HelloProvider() {
        super(Version.CORE_API, "hello-demo");
    }

    @Override
    public ToolGroupProvider.Descriptor describe() {
        return new ToolGroupProvider.Descriptor(
                "echo",                          // 组短名（限定名的末段）
                "示例/入门",                      // 分类
                "回声示例工具组（教程用）",           // 简介——必填
                "接入指南配套示例：演示 SPI 接入的最小闭环。",
                ToolRegistry.Availability.NORMAL,
                List.of(new ToolRegistry.Tool(
                        "echo",
                        "回显文本",
                        "echo(text) → 原样返回 text",
                        Map.of("type", "object"))));  // params 必填（结构化字段）
    }
}
