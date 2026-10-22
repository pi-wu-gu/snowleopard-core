package snowleopard.core;

/**
 * 版本与契约号（M0 骨架，2026-10-22 22:20 (UTC+8)）。
 *
 * <p>{@link #CORE_API} 是<b>契约版本</b>，与 {@code VERSION}（发布版本）独立演进：
 * 破坏性契约变更升 {@code CORE_API}，宿主装载时按它做兼容检查，不匹配明确报错而不是静默降级
 * （见 `docs/开发流程-2026-10-22.md` §七）。
 */
public final class Version {

    /** 契约版本：ToolGroupProvider / ToolBackend / HostServices / Result 等冻结接口的版本。 */
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
