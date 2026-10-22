package snowleopard.core.spi;

import java.util.List;

import snowleopard.core.registry.ToolRegistry;

/**
 * 外部工具组接入契约（M6，2026-10-22 23:36 (UTC+8)，分析 §四 / §二.二.3）。
 *
 * <p>其他模组（经 mod-shell）或外部目录实现本接口即可自动接入，<b>来源强制标注</b>：
 * {@link #sourceId()} 非空才有资格注册，限定名由装载器按
 * {@code <类型>:<sourceId>:<组名>} 拼——provider 自己<b>不构造</b>限定名，
 * 从能力上杜绝"冒名顶替别的来源"。
 *
 * <p><b>{@link #coreApi()} 不匹配即拒载</b>（开发流程 M6 验收）：契约破坏性变更升
 * {@code Version.CORE_API}，旧 provider 明确报错而不是静默半接入。
 */
public interface ToolGroupProvider {

    /**
     * 要求的契约号。与 {@link snowleopard.core.Version#CORE_API} 不等 → 拒载。
     * 相等才装载——宁可拒也不猜兼容（"看起来能跑"的半兼容是漂移温床）。
     */
    int coreApi();

    /**
     * 来源 ID：模组 modid / 外部目录的稳定 gid。<b>不能为空</b>。
     * 装载器据此拼限定名并标注来源；审计与搜索过滤都认这个字段。
     */
    String sourceId();

    /** 组描述（不含限定名——那由装载器按来源拼）。 */
    Descriptor describe();

    /** 组描述：组短名 + 简介（必填）+ 详解（可选）+ 分类 + 可用性 + 工具清单。 */
    record Descriptor(String group, String category, String summary, String details,
                      ToolRegistry.Availability availability,
                      List<ToolRegistry.Tool> tools) {

        public Descriptor {
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("组短名不能为空");
            }
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("工具组必须带简介（§一.2）: " + group);
            }
            tools = tools == null ? List.of() : List.copyOf(tools);
            availability = availability == null ? ToolRegistry.Availability.NORMAL : availability;
        }
    }

    /**
     * 便捷实现骨架：provider 只给组短名、来源、简介与工具，其余用默认。
     *（外部测试工具组常用——验收里"经 SPI 注册"的就是它。）
     */
    abstract class Simple implements ToolGroupProvider {
        private final int coreApi;
        private final String sourceId;

        protected Simple(int coreApi, String sourceId) {
            this.coreApi = coreApi;
            this.sourceId = sourceId;
        }

        @Override
        public final int coreApi() {
            return coreApi;
        }

        @Override
        public final String sourceId() {
            return sourceId;
        }
    }
}
