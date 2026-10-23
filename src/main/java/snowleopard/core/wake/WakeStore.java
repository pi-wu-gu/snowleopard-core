package snowleopard.core.wake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import snowleopard.core.ids.Ids;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

/**
 * 唤醒请求与策略（M1，2026-10-23 21:45 (UTC+8)，分析 §三 / 裁决 1–2）。
 *
 * <p><b>core 只登记与裁决，不执行</b>：真唤起（启动一次会话）是宿主的动作——它 {@code poll()} 取
 * {@code state=pending} 的请求后执行并回写。core 若假装能"让模型跑起来"，就只能在报告里说谎。
 *
 * <p><b>六道闸（顺序即语义）</b>：ID 合法 → 非自唤起 → 非成环 → 深度 ≤ 1 → 目标不在运行 → 未被目标拉黑。
 * 任一道失败都返回 {@link Result.Error} 带原因；<b>不排队</b>（排队 = 隐式并发，把"失败"变成"稍后"）。
 *
 * <p><b>黑名单属被唤起方</b>（{@code ids/<目标>/wake-policy.json}）：语义是"我不接受谁唤起我"。
 * 做成发起方的名单是另一个（且无意义的）功能。
 *
 * <p><b>不可伪造</b>：{@code from} 与 {@link Chain} 都由 core 注入（调用上下文），绝不来自工具参数。
 */
public final class WakeStore {

    /** 单 ID 被唤起配额：一小时内最多 20 次（防唤醒风暴）。 */
    public static final int QUOTA_PER_WINDOW = 20;
    public static final long WINDOW_MS = 60 * 60 * 1000L;

    /** 唤起链（从最初发起者到当前发起者；core 注入）。 */
    public record Chain(List<String> ids) {
        public Chain {
            ids = List.copyOf(ids);
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("唤起链不能为空");
            }
        }

        /** 已经走过的跳数：0 = 最初发起者（唯一有权唤起的深度）。 */
        public int depth() {
            return ids.size() - 1;
        }
    }

    /** 一次请求的快照（对外形态）。 */
    public record Request(String reqId, String from, String to, String prompt, String reason,
                          int depth, String state, long ts, String outcome) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reqId", reqId);
            m.put("from", from);
            m.put("to", to);
            m.put("prompt", prompt == null ? "" : prompt);
            m.put("reason", reason == null ? "" : reason);
            m.put("depth", depth);
            m.put("state", state);
            m.put("ts", ts);
            m.put("outcome", outcome == null ? "" : outcome);
            return m;
        }
    }

    /** 运行态 + 本轮是否已提示（提示一次由宿主记账，这里只透传 recent）。 */
    private final Ids ids;
    private final RuntimeView runtime;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public WakeStore(Ids ids, RuntimeView runtime) {
        if (ids == null) {
            throw new IllegalArgumentException("ids 必填");
        }
        this.ids = ids;
        this.runtime = runtime == null ? new LockFileRuntime(ids.shared()) : runtime;
    }

    public RuntimeView runtime() {
        return runtime;
    }

    // ------------------------------------------------------------- 发起

    /**
     * 发起唤醒。{@code chain} 由 core 注入（含发起者自身，如 {@code ["alpha"]}）。
     *
     * @param clientReqId 幂等键（重发同键 → WARN，不产生第二条）
     */
    public Result request(Chain chain, String from, String to, String prompt, String reason,
                          String clientReqId) {
        String fromId;
        String toId;
        try {
            fromId = new Ids.Caller(from).id();
            toId = new Ids.Caller(to).id();
        } catch (IllegalArgumentException e) {
            return Result.error("ID 不合法：" + e.getMessage());
        }
        // ① 自唤起
        if (fromId.equals(toId)) {
            return Result.error("不能唤起自身（" + fromId + "）");
        }
        // ② 成环
        if (chain.ids().contains(toId)) {
            return Result.error("唤起链成环：" + String.join("→", chain.ids()) + "→" + toId);
        }
        // ③ 深度（裁决：默认 1，被唤起方不得再唤起）
        if (chain.depth() >= 1) {
            return Result.error("唤起深度超限（当前调用方深度 " + chain.depth()
                    + "，上限 0）——被唤起方不得再唤起其他 ID");
        }
        // ④ 目标登记（ID 家的目录存在才算登记过）
        if (!Files.isDirectory(ids.home(toId))) {
            return Result.error("目标 ID 未登记：" + toId + "（它的家目录不存在）");
        }
        synchronized (lock()) {
            // ⑤ 目标在运行 → 失败（不排队）
            if (runtime.isRunning(toId)) {
                return Result.error("目标 " + toId + " 正在运行（运行态来源：" + runtime.source()
                        + "）——草案要求失败，不排队");
            }
            // ⑥ 被目标拉黑
            if (denyList(toId).contains(fromId)) {
                return Result.error("目标 " + toId + " 已禁止你唤起它（wake-policy 命中）");
            }
            // ⑦ 幂等
            if (clientReqId != null && !clientReqId.isBlank()) {
                Request dup = findByClientKey(fromId, toId, clientReqId);
                if (dup != null) {
                    return Result.warn(dup.toMap(), "同幂等键的唤起请求已存在（" + dup.reqId()
                            + "），未产生新请求");
                }
            }
            // ⑧ 配额（滑动窗口）
            long now = System.currentTimeMillis();
            long recent = countRecent(toId, now - WINDOW_MS);
            if (recent >= QUOTA_PER_WINDOW) {
                return Result.error("目标 " + toId + " 一小时内已被唤起 " + recent
                        + " 次（上限 " + QUOTA_PER_WINDOW + "）——疑似唤醒风暴，本次拒绝");
            }
            Request req = new Request(nextReqId(), fromId, toId, prompt, reason,
                    chain.depth() + 1, "pending", now, "");
            write(req, clientReqId);
            return Result.ok(req.toMap());
        }
    }

    /** 宿主回写执行结果（done / failed）。core 不判断语义，只落账。 */
    public Result complete(String reqId, boolean ok, String outcome) {
        synchronized (lock()) {
            Request r = load(reqId);
            if (r == null) {
                return Result.error("唤醒请求不存在：" + reqId);
            }
            if (!"pending".equals(r.state())) {
                return Result.warn(r.toMap(), "请求已是 " + r.state() + " 状态，未重复回写");
            }
            Request done = new Request(r.reqId(), r.from(), r.to(), r.prompt(), r.reason(),
                    r.depth(), ok ? "done" : "failed", r.ts(), outcome);
            write(done, clientKeyOf(reqId));
            return Result.ok(done.toMap());
        }
    }

    /** 宿主取待执行请求（按时间升序）。core 无权执行，只交出清单。 */
    public List<Request> pending() {
        synchronized (lock()) {
            List<Request> out = new ArrayList<>();
            for (Path p : reqFiles()) {
                Request r = read(p);
                if (r != null && "pending".equals(r.state())) {
                    out.add(r);
                }
            }
            out.sort(Comparator.comparingLong(Request::ts));
            return out;
        }
    }

    /** 查询单条（仅发起方与目标方可查）。 */
    public Result status(String actor, String reqId) {
        Request r = load(reqId);
        if (r == null) {
            return Result.error("唤醒请求不存在：" + reqId);
        }
        if (!r.from().equals(actor) && !r.to().equals(actor)) {
            return Result.error("无权查看他人唤起请求（" + reqId + "）");
        }
        return Result.ok(r.toMap());
    }

    // ------------------------------------------------------------- 黑名单（被唤起方）

    /** 拉黑 / 取消拉黑：改的是**调用方自己**的策略（"我不接受谁唤起我"）。 */
    public Result deny(String actor, String modelId, boolean on) {
        String target;
        try {
            target = new Ids.Caller(modelId).id();
        } catch (IllegalArgumentException e) {
            return Result.error("ID 不合法：" + e.getMessage());
        }
        if (actor.equals(target)) {
            return Result.error("不能把自己加入禁止唤起列表（自唤起本就恒拒）");
        }
        synchronized (lock()) {
            List<String> deny = new ArrayList<>(denyList(actor));
            if (on) {
                if (deny.contains(target)) {
                    return Result.warn(Map.of("deny", deny), "已在禁止列表中（幂等）");
                }
                deny.add(target);
            } else {
                if (!deny.remove(target)) {
                    return Result.warn(Map.of("deny", deny), "不在禁止列表中（幂等）");
                }
            }
            savePolicy(actor, deny);
            return Result.ok(Map.of("deny", deny));
        }
    }

    /** 我的策略：黑名单 + 最近被唤起记录（审计可见）。 */
    public Result policy(String actor) {
        synchronized (lock()) {
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Path p : reqFiles()) {
                Request r = read(p);
                if (r != null && r.to().equals(actor)) {
                    recent.add(r.toMap());
                }
            }
            recent.sort(Comparator.comparingLong(m -> -((Number) m.get("ts")).longValue()));
            if (recent.size() > 10) {
                recent = recent.subList(0, 10);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", actor);
            d.put("deny", denyList(actor));
            d.put("recent", recent);
            d.put("running", runtime.isRunning(actor) ? "yes" : "no");
            d.put("quotaPerHour", QUOTA_PER_WINDOW);
            return Result.ok(d);
        }
    }

    // ------------------------------------------------------------- 内部

    private Object lock() {
        return LOCKS.computeIfAbsent(ids.shared().toAbsolutePath().normalize(), k -> new Object());
    }

    private Path dir() {
        return ids.shared().resolve("wake");
    }

    private List<Path> reqFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) {
            return out;
        }
        try (var walk = Files.list(dir())) {
            walk.filter(p -> p.getFileName().toString().startsWith("w")
                    && p.getFileName().toString().endsWith(".json")).forEach(out::add);
        } catch (IOException ignored) {
            // 目录读不到 = 没有请求
        }
        return out;
    }

    private String nextReqId() {
        int max = 0;
        for (Path p : reqFiles()) {
            String n = p.getFileName().toString().replace("w", "").replace(".json", "");
            try {
                max = Math.max(max, Integer.parseInt(n));
            } catch (NumberFormatException ignored) {
                // 非序号文件忽略
            }
        }
        return String.format(Locale.ROOT, "w%05d", max + 1);
    }

    private Path fileOf(String reqId) {
        return dir().resolve(reqId + ".json");
    }

    private void write(Request r, String clientReqId) {
        try {
            Files.createDirectories(dir());
            Map<String, Object> m = r.toMap();
            if (clientReqId != null && !clientReqId.isBlank()) {
                m.put("clientReqId", clientReqId);
            }
            Files.writeString(fileOf(r.reqId()), Json.dump(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("唤醒请求落盘失败: " + e.getMessage(), e);
        }
    }

    private Request load(String reqId) {
        return read(fileOf(String.valueOf(reqId)));
    }

    private Request read(Path p) {
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            Map<String, Object> m = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
            return new Request(str(m, "reqId"), str(m, "from"), str(m, "to"), str(m, "prompt"),
                    str(m, "reason"), (int) num(m, "depth", 0), str(m, "state"),
                    (long) num(m, "ts", 0), str(m, "outcome"));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private String clientKeyOf(String reqId) {
        try {
            Map<String, Object> m = Json.parseObject(
                    Files.readString(fileOf(reqId), StandardCharsets.UTF_8));
            return str(m, "clientReqId");
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private Request findByClientKey(String from, String to, String clientKey) {
        for (Path p : reqFiles()) {
            try {
                Map<String, Object> m = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
                if (clientKey.equals(str(m, "clientReqId"))
                        && from.equals(str(m, "from")) && to.equals(str(m, "to"))) {
                    return read(p);
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // 坏文件跳过（不因一条坏记录锁死全组）
            }
        }
        return null;
    }

    private long countRecent(String to, long sinceTs) {
        long n = 0;
        for (Path p : reqFiles()) {
            Request r = read(p);
            if (r != null && r.to().equals(to) && r.ts() >= sinceTs) {
                n++;
            }
        }
        return n;
    }

    private Path policyFile(String actor) {
        return ids.home(actor).resolve("wake-policy.json");
    }

    private List<String> denyList(String actor) {
        Path p = policyFile(actor);
        if (!Files.isRegularFile(p)) {
            return List.of();
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
            Object v = m.get("deny");
            List<String> out = new ArrayList<>();
            if (v instanceof List<?> l) {
                for (Object o : l) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
    }

    private void savePolicy(String actor, List<String> deny) {
        try {
            Path p = policyFile(actor);
            Files.createDirectories(p.getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", actor);
            m.put("deny", deny);
            m.put("ts", System.currentTimeMillis());
            Files.writeString(p, Json.dump(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("唤醒策略落盘失败: " + e.getMessage(), e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static double num(Map<String, Object> m, String k, double dflt) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }
}
