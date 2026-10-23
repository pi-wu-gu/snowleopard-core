package snowleopard.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import snowleopard.core.builtin.BuiltinGroups;
import snowleopard.core.builtin.BuiltinHome;
import snowleopard.core.builtin.BuiltinToolkit;
import snowleopard.core.confirm.ConfirmGate;
import snowleopard.core.ids.Ids;
import snowleopard.core.memory.Compressor;
import snowleopard.core.memory.Corrections;
import snowleopard.core.memory.MemoryStore;
import snowleopard.core.memory.RawLog;
import snowleopard.core.memory.Rollup;
import snowleopard.core.names.Names;
import snowleopard.core.notes.NoteStore;
import snowleopard.core.perm.Permissions;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.report.StartupReport;
import snowleopard.core.result.Result;
import snowleopard.core.session.SessionGate;
import snowleopard.core.spi.ProviderLoader;
import snowleopard.core.spi.ToolGroupProvider;
import snowleopard.core.todo.TodoStore;

/**
 * 装配门面（波次 5A，2026-10-23 01:55 (UTC+8)，方案 A——两级句柄）。
 *
 * <p>把标准装配链固化成一个入口：<b>root 级</b>（{@link Ids}/{@link ToolRegistry}/
 * {@link ProviderLoader}/{@link Permissions}，全 caller 共享）+ <b>caller 级</b>
 * （{@link CallerScope}，"ID 文件夹=模型的家"，各能力一份）。
 *
 * <p><b>API note</b>（冻结契约，S4 additive-only）：签名只增不改；新能力先进组件、
 * 门面只在有装配意义时开口——不"顺便挂能力"。
 *
 * <p><b>S1 无特权路径</b>：本类只调用各组件的公开构造/工厂，装配路径=手动装配的展开式，
 * 不存在只有门面知道的私有步骤。手动装配示例（examples 的 HelloManualMain）常绿保活证明这一点。
 *
 * <p><b>S5 生命周期无关</b>：无 start/stop/MC 钩子；reload/unload 等控制点保留在组件层，
 * 通过访问器转发（{@link #loader()}/{@link #permissions()}）。
 */
public final class SnowLeopard {

    private final Ids ids;
    private final ToolRegistry registry;
    private final ProviderLoader loader;
    private final Permissions permissions;
    private final Options options;
    /** root 级：唤醒请求（宿主 poll/complete 用；策略与已读位仍按 actor 落各自的家）。 */
    private final snowleopard.core.wake.WakeStore wake;

    private SnowLeopard(Ids ids, ToolRegistry registry, ProviderLoader loader,
                        Permissions permissions, Options options,
                        snowleopard.core.wake.WakeStore wake) {
        this.ids = ids;
        this.registry = registry;
        this.loader = loader;
        this.permissions = permissions;
        this.options = options;
        this.wake = wake;
    }

    /**
     * 标准装配入口。顺序即依赖：Ids → ToolRegistry → <b>内置四组</b> → ProviderLoader → Permissions。
     *
     * @param storageRoot 存储根（全部 ID 家的父目录）
     * @param options     装配选项——<b>必填</b>：权限政策不设静默默认（S3，全开/文件二选一要显式说）
     */
    public static SnowLeopard bootstrap(Path storageRoot, Options options) {
        if (storageRoot == null) {
            throw new IllegalArgumentException("storageRoot 必填（S3：不猜存储根）");
        }
        if (options == null) {
            throw new IllegalArgumentException("options 必填（S3：权限政策必须显式——allowAll() 或 fromFiles()）");
        }
        Ids ids = Ids.at(storageRoot);
        ToolRegistry registry = new ToolRegistry();
        ProviderLoader loader = new ProviderLoader(registry);
        // 内置四组（builtin:core:session|memory|todo|notes）走与外部 provider 同一条装载路径——
        // 不享受特权通道（分析 §4.4：内置不是绕过注册表的影子能力）。
        // 必须在 Permissions 之前：权限文件要按已注册的组名校验条目，全权限快照也要罩住它们。
        for (ToolGroupProvider p : BuiltinGroups.all()) {
            loader.load(p, Names.SourceType.BUILTIN);
        }
        Permissions permissions = options.materializePermissions(registry);
        // 一个 WakeStore 全 caller 共用：请求落 shared/wake，策略与已提示位落 ids/<actor>/（红线 3 不变）
        snowleopard.core.wake.WakeStore wake =
                new snowleopard.core.wake.WakeStore(ids, options.runtime());
        return new SnowLeopard(ids, registry, loader, permissions, options, wake);
    }

    // ── root 级（共享）──

    public Ids ids() {
        return ids;
    }

    public ToolRegistry registry() {
        return registry;
    }

    /** 装载/卸载的控制点在装载器上（S5：门面只转发，不吞生命周期）。 */
    public ProviderLoader loader() {
        return loader;
    }

    /** 唤醒请求的宿主侧视图（{@code pending()} / {@code complete()} 不需要 actor）。 */
    public snowleopard.core.wake.WakeStore wake() {
        return wake;
    }

    /** 热更新走 {@link Permissions#reload}（不回溯）——同样保留原控制点。 */
    public Permissions permissions() {
        return permissions;
    }

    /** 启动报告：来源分布 · 消歧表 · WARN 汇总 · 门面默认值提示（S3 可观测）。 */
    public List<String> startupReport() {
        List<String> extra = new ArrayList<>();
        extra.add("装配门面默认值: 压缩器=" + options.compressorLabel()
                + " · 预算=" + (options.budget().turnsLimited() || options.budget().tokensLimited()
                ? "有限" : "无上限（SessionGate.Budget.unlimited）"));
        return StartupReport.render(registry, registry.names(), loader.issues(),
                options.configSrc(), extra);
    }

    // ── caller 级（每模型一份）──

    /**
     * 进入某个调用方的家。ID 由 {@link Ids.Caller} 校验（字母数字开头，≤64）；
     * <b>caller_id 不可伪造</b>（红线 6）——这里是它进入系统的唯一入口。
     */
    public CallerScope forCaller(String callerId) {
        return new CallerScope(this, new Ids.Caller(callerId));
    }

    /**
     * caller 级句柄：能力对象全挂在调用 ID 的家下、互相隔离（红线 3）。
     * 持有会话与确认状态（{@link #session()}/{@link #confirm()}）——一次会话一个 scope；
     * 存储类是文件视图，重建不丢数据。
     *
     * <p>实现 {@link BuiltinHome}：内置四组的执行面（{@link #tools()}）只认这一个接口，
     * 依赖方向单向（门面 → 内置包），不制造包级双向依赖。
     */
    public static final class CallerScope implements BuiltinHome {

        private final Ids.Caller caller;
        private final Path home;
        private final TodoStore todos;
        private final NoteStore notes;
        private final MemoryStore memory;
        private final RawLog rawLog;
        private final Rollup rollup;
        private final Corrections corrections;
        private final SessionGate session;
        private final ConfirmGate confirm;
        private final snowleopard.core.wake.WakeStore wake;
        private final snowleopard.core.group.GroupStore groups;
        /** 唤起链（core 注入；被唤起启动的会话为 [from, self]）。 */
        private volatile List<String> wakeChain;

        private CallerScope(SnowLeopard core, Ids.Caller caller) {
            this.caller = caller;
            this.home = core.ids.home(caller);
            this.todos = new TodoStore(core.ids, caller);
            this.notes = new NoteStore(core.ids, caller);
            this.memory = new MemoryStore(core.ids, caller);
            this.rawLog = new RawLog(core.ids, caller);
            this.rollup = new Rollup(rawLog, memory, core.options.compressor(), core.ids, caller);
            this.corrections = new Corrections(core.ids, caller);
            this.session = new SessionGate(core.options.budget());
            this.confirm = new ConfirmGate();
            // 协作面：请求/消息落 shared/，策略与已读位落各自的家（红线 3 对核心组件的隔离不受影响）
            this.wake = core.wake;   // 与 root 级共用（方法都带 actor 参数，状态仍按 ID 落盘）
            this.groups = new snowleopard.core.group.GroupStore(core.ids);
            this.wakeChain = List.of(caller.id());
        }

        public Ids.Caller caller() {
            return caller;
        }

        /** 调用方 ID（红线 6：core 注入，永远不是工具参数）。 */
        @Override
        public String callerId() {
            return caller.id();
        }

        /** 家目录（工具工作目录默认挂这里）。 */
        public Path home() {
            return home;
        }

        public TodoStore todos() {
            return todos;
        }

        public NoteStore notes() {
            return notes;
        }

        public MemoryStore memory() {
            return memory;
        }

        public RawLog rawLog() {
            return rawLog;
        }

        public Rollup rollup() {
            return rollup;
        }

        public Corrections corrections() {
            return corrections;
        }

        public SessionGate session() {
            return session;
        }

        public ConfirmGate confirm() {
            return confirm;
        }

        /**
         * 内置四组的执行面（{@code builtin:core:session|memory|todo|notes}）。
         * 宿主按注册表拿到的 schema 路由到 {@link BuiltinToolkit#call}——
         * 一次装配一个实例，执行面只认本 scope 的家（红线 3）。
         */
        public BuiltinToolkit tools() {
            return new BuiltinToolkit(this);
        }

        /** 协作面：唤醒请求与策略。 */
        @Override
        public snowleopard.core.wake.WakeStore wake() {
            return wake;
        }

        /** 协作面：本地群组。 */
        @Override
        public snowleopard.core.group.GroupStore groups() {
            return groups;
        }

        /** 唤起链（core 注入；模型无法通过工具参数改写）。 */
        @Override
        public List<String> wakeChain() {
            return wakeChain;
        }

        /**
         * 宿主专用：本会话是被别的 ID 唤起启动时设置链（如 {@code ["alpha", "beta"]}）。
         * 链必须以本 ID 结尾——否则深度判定会被绕过。
         */
        public CallerScope withWakeChain(List<String> ids) {
            if (ids == null || ids.isEmpty()
                    || !ids.get(ids.size() - 1).equals(caller.id())) {
                throw new IllegalArgumentException("唤起链不能为空且必须以当前 ID 结尾");
            }
            this.wakeChain = List.copyOf(ids);
            return this;
        }
    }

    /**
     * 装配选项（S3：<b>默认值即政策</b>，每个默认一句话立场，随 API 发布）。
     *
     * <ul>
     *   <li><b>权限</b>：无默认——{@link #allowAll()} 与 {@link #fromFiles} 必须显式二选一
     *       （全开是危险默认，不许静默继承）；</li>
     *   <li><b>压缩器</b>：默认=直通（不调 LLM，摘要=原文）——缺省可跑但报告里点名，不静默；</li>
     *   <li><b>预算</b>：默认=无上限（{@link SessionGate.Budget#unlimited()}）——收束由宿主决定；</li>
     *   <li><b>configSrc</b>：默认=按权限选择生成描述，进启动报告。</li>
     * </ul>
     */
    public static final class Options {

        private final boolean allowAll;
        private final Path input;
        private final Path bundled;
        private final Compressor compressor;
        private final SessionGate.Budget budget;
        private final String configSrc;
        /** 运行态权威（缺省=单机双保险 LockFileRuntime，由 CallerScope 惰性建）。 */
        private final snowleopard.core.wake.RuntimeView runtime;

        private Options(boolean allowAll, Path input, Path bundled,
                        Compressor compressor, SessionGate.Budget budget, String configSrc,
                        snowleopard.core.wake.RuntimeView runtime) {
            this.allowAll = allowAll;
            this.input = input;
            this.bundled = bundled;
            this.compressor = compressor;
            this.budget = budget;
            this.configSrc = configSrc;
            this.runtime = runtime;
        }

        /** 显式全开（教程/示例用；生产请 {@link #fromFiles}）。 */
        public static Options allowAll() {
            return new Options(true, null, null, passThrough(), SessionGate.Budget.unlimited(),
                    "全权限（Permissions.defaultAll——显式选择）", null);
        }

        /** 显式权限文件（input 优先于 bundled；热更新不回溯）。 */
        public static Options fromFiles(Path input, Path bundled) {
            return new Options(false, input, bundled, passThrough(), SessionGate.Budget.unlimited(),
                    "权限文件（input=" + input + "，bundled=" + bundled + "）", null);
        }

        /** 注入压缩器（LLM 压缩由宿主实现 {@link Compressor}；失败抛异常=冷却补做）。 */
        public Options withCompressor(Compressor compressor) {
            if (compressor == null) {
                throw new IllegalArgumentException("compressor 不接受 null（缺省=直通，别传 null）");
            }
            return new Options(allowAll, input, bundled, compressor, budget, configSrc, runtime);
        }

        /** 设置会话预算（默认无上限）。 */
        public Options withBudget(SessionGate.Budget budget) {
            if (budget == null) {
                throw new IllegalArgumentException("budget 不接受 null（缺省=unlimited）");
            }
            return new Options(allowAll, input, bundled, compressor, budget, configSrc, runtime);
        }

        /** 注入运行态权威（宿主实现；缺省=单机双保险）。 */
        public Options withRuntime(snowleopard.core.wake.RuntimeView runtime) {
            if (runtime == null) {
                throw new IllegalArgumentException("runtime 不接受 null（缺省用 LockFileRuntime，别传 null）");
            }
            return new Options(allowAll, input, bundled, compressor, budget, configSrc, runtime);
        }

        snowleopard.core.wake.RuntimeView runtime() {
            return runtime;
        }

        /** 覆盖启动报告里的配置来源描述。 */
        public Options withConfigSrc(String desc) {
            return new Options(allowAll, input, bundled, compressor, budget, desc, runtime);
        }

        private Permissions materializePermissions(ToolRegistry registry) {
            return allowAll ? Permissions.defaultAll(registry)
                    : Permissions.fromFiles(registry, input, bundled);
        }

        private Compressor compressor() {
            return compressor;
        }

        private SessionGate.Budget budget() {
            return budget;
        }

        private String configSrc() {
            return configSrc;
        }

        private String compressorLabel() {
            return compressor instanceof PassThrough
                    ? "直通（默认——不调 LLM，摘要=原文；withCompressor 可注入）"
                    : "自定义（宿主注入）";
        }

        /** 默认压缩器语义（S3）：不压缩但链路可跑——绝不静默 NPE、绝不假装压缩过。 */
        private static final class PassThrough implements Compressor {
            @Override
            public String compress(String purpose, String source) {
                return source;
            }
        }

        private static Compressor passThrough() {
            return new PassThrough();
        }
    }
}
