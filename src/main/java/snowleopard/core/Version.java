package snowleopard.core;

/**
 * 版本与契约号（M0 骨架，2026-10-22 22:20 (UTC+8)）。
 *
 * <p>{@link #CORE_API} 是<b>契约版本</b>，与 {@code VERSION}（发布版本）独立演进：
 * 破坏性契约变更升 {@code CORE_API}，宿主装载时按它做兼容检查，不匹配明确报错而不是静默降级
 * （见 `docs/开发流程-2026-10-22.md` §七）。
 */
public final class Version {

    /**
     * 契约版本：ToolGroupProvider / Result 等冻结接口的版本。
     *
     * <p><b>API note</b>（冻结契约，2026-10-23 01:55 (UTC+8) 波次 4 标注）：
     * 破坏性契约变更才升号；provider 侧 {@code coreApi()} 不等于本值即拒载——
     * 宿主与工具组的兼容性判定只认这一个数字。
     */
    public static final int CORE_API = 1;

    private Version() {
    }

    /** 发布版本（由构建写入清单；这里只做兜底显示）。 */
    public static String release() {
        return Version.class.getPackage() == null
                ? "unknown"
                : Version.class.getPackage().getImplementationVersion();
    }
}
