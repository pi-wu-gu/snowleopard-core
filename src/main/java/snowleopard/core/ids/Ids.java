package snowleopard.core.ids;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ID 文件夹（"模型的家"）与调用方 ID 注入（M1，分析 §九.1/分析 §九.3/分析 §9.6）。
 *
 * <p><b>三个不可让步的约束</b>：
 * <ul>
 *   <li><b>挂载隔离</b>：所有工具工作目录默认挂 {@code ids/<ID>/} 下，ID 之间互相隔离
 *       （含只读）——工具是功能复用，不复用信息（21:07 裁决）；</li>
 *   <li><b>路径锚定</b>：解析强制锚定 {@code ids/<ID>/} 前缀，越界即拒——从能力上杜绝逃逸；</li>
 *   <li><b>caller_id 不可伪造</b>：只由核心的会话归属注入（{@link Caller}），
 *       不接受模型侧覆盖；工具若声明"自定义数据源"参数，装载期即拒（分析 §9.6）。</li>
 * </ul>
 */
public final class Ids {

    /**
     * 调用方身份：核心注入，模型无法伪造（星期五 Actor 机制的移植）。
     *
     * <p><b>API note</b>（冻结契约，2026-10-23 01:55 (UTC+8) 波次 4 标注）：
     * caller_id 走调用上下文由 core 注入，<b>永远不是工具参数</b>——工具签名里不允许出现它。
     */
    public record Caller(String id) {

        public Caller {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("非法 ID: " + id
                        + "（字母数字开头，可含 _-，≤64 字符）");
            }
        }
    }

    private final Path root; // <storage.root>/ids

    private Ids(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** storage.root 下的 ids/ 目录。 */
    public static Ids at(Path storageRoot) {
        return new Ids(storageRoot.resolve("ids"));
    }

    public Path root() {
        return root;
    }

    /**
     * 共享面根（{@code <storage>/shared}）——协作类能力（群组 / 唤醒请求与策略）唯一的跨 ID 内容区。
     *
     * <p>红线 3 只约束**核心组件的数据**（记忆/笔记/待办/通用文件访问不跨 ID 复用信息）；
     * 共享面是裁决允许的例外，三条约束见概念地图 §三·补（零路径参数 / 鉴权在 core / caller_id 不可伪造）。
     */
    public Path shared() {
        return root.getParent().resolve("shared");
    }

    /** 该 ID 的家目录（不创建文件系统对象——创建交给 mkdirs）。 */
    public Path home(Caller caller) {
        return root.resolve(caller.id());
    }

    public Path home(String id) {
        return home(new Caller(id));
    }

    /**
     * 解析挂载路径并强制锚定在调用方 ID 家内。
     *
     * @param rel 相对挂载点（如 {@code notes/}、{@code memory-raw/}）；{@code ..} 与绝对路径即拒
     * @throws PathEscape 逃逸（越出 ID 家）——ERROR 级，绝不静默收敛
     */
    public Path resolve(Caller caller, String rel) {
        if (rel == null || rel.isBlank()) {
            throw new IllegalArgumentException("挂载路径不能为空");
        }
        Path home = home(caller).normalize();
        Path p = home.resolve(rel).normalize();
        if (!p.startsWith(home)) {
            throw new PathEscape("路径越出 ID 家: " + rel + "（" + caller.id() + "）");
        }
        return p;
    }

    /**
     * mkdirs：把挂载点（含按需创建的分级桶）建出来。同样受锚定约束。
     * 分桶按需创建（稀疏性约束 分析 §10.5）：调用方决定建哪层，这里不替日历猜。
     */
    public Path mkdirs(Caller caller, String rel) {
        Path p = resolve(caller, rel);
        try {
            java.nio.file.Files.createDirectories(p);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建挂载目录失败: " + p, e);
        }
        return p;
    }

    /** 列出存在的 ID 家（稀疏：空 ID 不算缺口）。 */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        try (var s = java.nio.file.Files.list(root)) {
            s.filter(java.nio.file.Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        } catch (java.io.IOException e) {
            return List.of();
        }
        return out;
    }

    /** 路径逃逸（越出 ID 家）。ERROR 级：拒绝该次调用，不静默收敛到家内。 */
    public static final class PathEscape extends RuntimeException {
        public PathEscape(String message) {
            super(message);
        }
    }

    /**
     * 工具清单的"自定义数据源"参数检测（分析 §9.6 防伪要点）：
     * 参数名命中即装载期拒绝。
     */
    public static List<String> detectDataSourceParams(Map<String, Object> paramsSchema) {
        List<String> bad = new ArrayList<>();
        if (paramsSchema == null) {
            return bad;
        }
        for (String k : paramsSchema.keySet()) {
            String low = k.toLowerCase(java.util.Locale.ROOT);
            if (low.equals("id") || low.equals("caller_id") || low.equals("callerid")
                    || low.equals("source_id") || low.equals("data_source") || low.equals("home")) {
                bad.add(k);
            }
        }
        return bad;
    }
}
