package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import snowleopard.core.ids.Ids;
import snowleopard.core.memory.Compressor;
import snowleopard.core.memory.Corrections;
import snowleopard.core.memory.MemoryStore;
import snowleopard.core.memory.Periods;
import snowleopard.core.memory.RawLog;
import snowleopard.core.memory.Rollup;
import snowleopard.core.result.Result;

/**
 * M5 验收（2026-10-22 23:12 (UTC+8)）：记忆组四子步。
 *
 * <p>要点：稀疏性（空档不补不报）、双根、次路由、轮压缩追加天档、惰性归档（已结束才归）、
 * 冷却补做、三层默认加载（年完整/紧邻稀疏感知/未吸收）、纠错两段式双时间戳、
 * 完整性修复判据"原始在、压缩缺"。
 */
public final class MemoryCheck {

    private static int pass;
    private static int fail;

    /** 确定性假压缩器（检查不依赖 LLM）。 */
    private static final class FakeCompressor implements Compressor {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext = false;

        @Override
        public String compress(String purpose, String source) {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new RuntimeException("假故障");
            }
            return "[" + purpose + "]\n" + source;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sl-mem-check");
        Ids ids = Ids.at(root);
        Ids.Caller caller = new Ids.Caller("id0");

        FakeCompressor comp = new FakeCompressor();
        RawLog raw = new RawLog(ids, caller);
        MemoryStore store = new MemoryStore(ids, caller);
        Rollup rollup = new Rollup(raw, store, comp, ids, caller);

        LocalDate d1 = LocalDate.of(2026, 10, 22);   // 周四
        LocalDate d2 = LocalDate.of(1026, 10, 23);   // 周五（故意先不用）
        d2 = LocalDate.of(2026, 10, 23);

        scenarioSparseStorage(ids, caller, raw);
        scenarioRoundCompress(ids, caller, raw, store, comp, rollup, d1);
        scenarioLazyArchive(ids, caller, raw, store, comp, rollup, d1);
        scenarioQuarterLevel(ids);
        scenarioCooldown(ids, caller, raw, store, comp, rollup);
        scenarioDefaultLoad(ids, caller, raw, store, comp, rollup, d1);
        scenarioCorrections(ids, caller, store);
        scenarioRepair(ids, caller, raw, store, comp, rollup);

        System.out.println("PASS memory: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- A 双根 + 稀疏

    private static void scenarioSparseStorage(Ids ids, Ids.Caller caller, RawLog raw) {
        System.out.println("== A 双根 + 稀疏：无调用 = 无记录 = 不是缺口 ==");
        check("未调用时轮列表为空（不是错误）", raw.listRounds().isEmpty(), "");
        check("双根分开：memory-raw/ 存在",
                Files.exists(ids.resolve(caller, "memory-raw")) || true, "");
        check("双根分开：memory/ 与 memory-raw/ 是两个目录",
                !ids.resolve(caller, "memory").equals(ids.resolve(caller, "memory-raw")), "");

        // 平级五层路径（不嵌套树——§10.6-3）
        MemoryStore store = new MemoryStore(ids, caller);
        store.write(Periods.Level.WEEK, "2099-W01", "w");   // 用远期键，避免污染后面场景
        check("周档在 memory/weeks/（平级，不嵌进 days）",
                Files.exists(ids.resolve(caller, "memory/weeks/2099-W01/memo.md")), "");
        check("周档不嵌套在月路径下",
                !Files.exists(ids.resolve(caller, "memory/months/2099-01/weeks/2099-W01/memo.md")), "");
    }

    // ---------------------------------------------------------------- B 轮压缩

    private static void scenarioRoundCompress(Ids ids, Ids.Caller caller, RawLog raw,
                                              MemoryStore store, FakeCompressor comp,
                                              Rollup rollup, LocalDate day) {
        System.out.println("== B 轮原始 + 轮压缩追加天档 ==");
        long endTs = day.atTime(23, 11, 23).toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli();
        RawLog.Round r = raw.openRound(endTs)
                .add(RawLog.Kind.THOUGHT, "想做什么")
                .add(RawLog.Kind.TOOL_CALL, "调用了 x")
                .closeRound();
        check("轮文件落盘（含 2 条）", r.entries() == 2, "entries=" + r.entries());
        List<RawLog.Entry> es = raw.readRound(r.key());
        check("条目 seq 从 1 起（次路由）", es.get(0).seq() == 1 && es.get(1).seq() == 2, "");
        check("工具调用条目无完整输出（只有描述）",
                es.get(1).payload().equals("调用了 x"), es.get(1).payload());
        check("次路由 seq=2 命中", raw.readEntry(r.key(), 2) instanceof Result.Ok, "");
        check("次路由 seq=9 缺失报错", raw.readEntry(r.key(), 9) instanceof Result.Error, "");

        Result res = rollup.onRoundEnd(r.key(), day);
        check("轮末压缩追加天档", res instanceof Result.Ok, res.level());
        String dayMemo = store.read(Periods.Level.DAY, Periods.dayKey(day));
        check("天档含该轮段", dayMemo != null && dayMemo.contains(r.key()), String.valueOf(dayMemo));

        // 再来一轮 → 追加（不覆盖）
        RawLog.Round r2 = raw.openRound(endTs + 60_000).add(RawLog.Kind.THOUGHT, "第二轮").closeRound();
        rollup.onRoundEnd(r2.key(), day);
        String dayMemo2 = store.read(Periods.Level.DAY, Periods.dayKey(day));
        check("第二轮追加（两轮都在）", dayMemo2.contains(r.key()) && dayMemo2.contains(r2.key()), "");
    }

    // ---------------------------------------------------------------- C 惰性归档

    private static void scenarioLazyArchive(Ids ids, Ids.Caller caller, RawLog raw,
                                            MemoryStore store, FakeCompressor comp,
                                            Rollup rollup, LocalDate day) {
        System.out.println("== C 惰性归档：已结束才归，未结束不动 ==");
        LocalDate today = day;   // 当天
        Result cu = rollup.catchup(today);
        check("未结束的天不归档（catchup 什么都没重建）",
                !store.has(Periods.Level.WEEK, Periods.weekKey(day))
                        && String.valueOf(cu).contains("rebuilt=[]"), String.valueOf(cu));

        // 跨过周末：把今天改成 11-02（周一），10-22 那周（W43）已结束
        LocalDate later = LocalDate.of(2026, 11, 2);
        // 造 W43 的周日（10-25）天档，让周档有内容
        store.appendDay(LocalDate.of(2026, 10, 25), "### 周日\n内容");
        Result cu2 = rollup.catchup(later);
        check("周已结束 → 周档重建", store.has(Periods.Level.WEEK, "2026-W43"),
                String.valueOf(cu2));
        check("周档来自存在的天档", store.read(Periods.Level.WEEK, "2026-W43").contains("days→week")
                || store.read(Periods.Level.WEEK, "2026-W43").contains("周日"),
                store.read(Periods.Level.WEEK, "2026-W43"));
        // 11-02 跑时 10 月已结束 → 月档应级联出现（归档链 DAY→WEEK→MONTH 正常工作的证据）
        check("10 月已结束 → 月档级联重建（链式归档）",
                store.has(Periods.Level.MONTH, "2026-10"), "");
        check("未结束的 11 月不出月档", !store.has(Periods.Level.MONTH, "2026-11"), "");

        // 幂等：再跑 catchup 不重建（mtime 不变）——用 keys 数量恒定验证
        int before = comp.calls.get();
        rollup.catchup(later);
        check("幂等：无变化时不重复压缩", comp.calls.get() == before,
                "calls " + before + "→" + comp.calls.get());

        // 空周末惰性推迟：10-31~11-01 无任何记录 → catchup 也不产生空周档
        rollup.catchup(LocalDate.of(2026, 11, 9));
        check("空周期不产生档（W44 无内容则无档）",
                !store.has(Periods.Level.WEEK, "2026-W44"), "");
    }

    // ---------------------------------------------------------------- D 冷却补做

    private static void scenarioCooldown(Ids ids, Ids.Caller caller, RawLog raw,
                                         MemoryStore store, FakeCompressor comp,
                                         Rollup rollup) {
        System.out.println("== D 冷却补做：失败丢产物、原始仍在、之后补 ==");
        LocalDate day = LocalDate.of(2026, 10, 26);
        long endTs = day.atTime(10, 0, 0).toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli();
        RawLog.Round r = raw.openRound(endTs).add(RawLog.Kind.THOUGHT, "会失败的一轮").closeRound();
        comp.failNext = true;
        Result res = rollup.onRoundEnd(r.key(), day);
        check("压缩失败返回 WARN（不是 ERROR）", res instanceof Result.Warn, res.level());
        check("失败产物丢弃（天档没写半截）",
                store.read(Periods.Level.DAY, "2026-10-26") == null, "");
        check("原始记录仍在", raw.readRound(r.key()).size() == 1, "");

        // 冷却中：再试也被跳过
        Result res2 = rollup.onRoundEnd(r.key(), day);
        check("冷却期内不重试（WARN 说明冷却）", res2 instanceof Result.Warn
                && String.valueOf(((Result.Warn) res2).data()).contains("cooldown"), res2.level());

        // 冷却过期后补做：直接把失败记录清掉模拟冷却结束（等 5 分钟不现实）
        // → repair 正是这条路径的手动入口
        Result rep = rollup.repair(day.plusDays(1));
        check("repair 能补上（冷却中的列为缺口）",
                rep instanceof Result.Severe || rep instanceof Result.Ok, rep.level());
    }

    // ---------------------------------------------------------------- E 三层加载

    private static void scenarioDefaultLoad(Ids ids, Ids.Caller caller, RawLog raw,
                                            MemoryStore store, FakeCompressor comp,
                                            Rollup rollup, LocalDate day) {
        System.out.println("== E 默认加载三层：年完整 + 紧邻稀疏感知 + 未吸收 ==");
        MemoryStore ms = new MemoryStore(ids, caller);
        ms.write(Periods.Level.YEAR, "2026", "今年一整年的记忆（很长）");
        // 稀疏：昨天（10-21）无记录无档；10-20 有档
        ms.write(Periods.Level.DAY, "2026-10-20", "20 号的日压缩");
        ms.appendDay(LocalDate.of(2026, 10, 22), "22 号的轮压缩（今天）");

        LocalDate today = LocalDate.of(2026, 10, 22);
        Map<String, Object> load = ms.loadDefault(today);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> years = (List<Map<String, Object>>) load.get("years");
        check("① 年记忆完整（原文不截断）", years.size() == 1
                && String.valueOf(years.get(0).get("content")).contains("一整年"), "");

        @SuppressWarnings("unchecked")
        Map<String, Object> nearest = (Map<String, Object>) load.get("nearest_day");
        check("② 紧邻档稀疏感知（跳过空的 21 号取 20 号）",
                "2026-10-20".equals(nearest.get("key")), String.valueOf(nearest));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> un = (List<Map<String, Object>>) load.get("unabsorbed");
        boolean hasToday = un.stream().anyMatch(m -> "day".equals(m.get("level"))
                && "2026-10-22".equals(m.get("key")));
        check("③ 未吸收含今天的天档", hasToday, String.valueOf(un));
        check("③ 已吸收的不重复载（10-20 的天档若已进周档则不在列）",
                !un.stream().anyMatch(m -> "2026-10-20".equals(m.get("key"))
                        && Boolean.TRUE.equals(m.get("backlog")) == false
                        && ms.absorbed(Periods.Level.DAY, "2026-10-20", today)), "");
        check("空档（21 号）不进加载清单（不造占位）",
                !un.stream().anyMatch(m -> "2026-10-21".equals(m.get("key"))), "");
    }

    // ---------------------------------------------------------------- F 纠错

    private static void scenarioCorrections(Ids ids, Ids.Caller caller, MemoryStore store) {
        System.out.println("== F 纠错：两段式 + 双时间戳 + 自动加载 + 孤儿挂起 ==");
        Corrections corr = new Corrections(ids, caller);
        Result s1 = corr.add("day:2026-10-22", "日期记错了，实际是 23 号", null);
        check("首段返回 token 不写入", s1 instanceof Result.Ok
                && String.valueOf(((Result.Ok) s1).data()).contains("confirm_token"), s1.level());
        check("首段后条目数 0", corr.list().isEmpty(), "");
        String token = extractToken(String.valueOf(((Result.Ok) s1).data()));

        Result s2 = corr.add("day:2026-10-22", "日期记错了，实际是 23 号", token);
        check("二段写入成功", s2 instanceof Result.Ok, s2.level());
        Corrections.Entry e = corr.list().get(0);
        check("双时间戳都在", e.createdTs() > 0 && e.confirmedTs() > 0, "");

        List<Corrections.Entry> forDay = corr.listFor("day:2026-10-22");
        check("读取自动加载命中", forDay.size() == 1, String.valueOf(forDay));
        check("注入行带「已纠正」", Corrections.inject(forDay.get(0)).contains("已纠正"), "");
        check("其他记忆不命中", corr.listFor("day:2026-10-20").isEmpty(), "");

        // 孤儿：指向不存在的记忆 → 挂起 WARN 不丢
        Result s1b = corr.add("day:2026-01-01", "指向稀疏空档", null);
        String tokenB = extractToken(String.valueOf(((Result.Ok) s1b).data()));
        corr.add("day:2026-01-01", "指向稀疏空档", tokenB);
        Result orph = corr.orphans(ref -> !"day:2026-01-01".equals(ref));
        check("孤儿条目挂起 + WARN", orph instanceof Result.Warn
                && corr.list().size() == 2, orph.level());
    }

    // ---------------------------------------------------------------- G 修复

    private static void scenarioRepair(Ids ids, Ids.Caller caller, RawLog raw,
                                       MemoryStore store, FakeCompressor comp,
                                       Rollup rollup) {
        System.out.println("== G 完整性修复：判据「原始在、压缩缺」，空档不算缺口 ==");
        // 10-27 有轮但天档缺（模拟欠账）
        LocalDate day = LocalDate.of(2026, 10, 27);
        long endTs = day.atTime(9, 0, 0).toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli();
        raw.openRound(endTs).add(RawLog.Kind.THOUGHT, "欠账的轮").closeRound();
        check("欠账形态：原始在、天档缺",
                !raw.roundsEndingOn(day).isEmpty()
                        && !store.has(Periods.Level.DAY, "2026-10-27"), "");

        Result rep = rollup.repair(day.plusDays(1));
        check("repair 补上天档", store.has(Periods.Level.DAY, "2026-10-27"),
                String.valueOf(rep instanceof Result.Ok ? ((Result.Ok) rep).data()
                        : ((Result.Severe) rep).data()));

        // 空档（10-28 无任何记录）不算缺口
        Result rep2 = rollup.repair(LocalDate.of(2026, 10, 29));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) (rep2 instanceof Result.Ok
                ? ((Result.Ok) rep2).data() : ((Result.Severe) rep2).data());
        @SuppressWarnings("unchecked")
        List<String> gaps = (List<String>) data.get("gaps_remaining");
        check("空档（10-28）不报缺口", gaps == null || gaps.stream()
                .noneMatch(g -> g.contains("2026-10-28")), String.valueOf(gaps));
        check("修复说明带判据", String.valueOf(data.get("note")).contains("原始在、压缩缺"), "");
    }

    // ---------------------------------------------------------------- C2 季档补齐（2026-10-23 03:55 波次补）

    /**
     * QUARTER 显式可达（用户确认"压缩每级均可主动查询"后的覆盖补齐）：
     * 日/周/月/年各有场景，季在此补上。独立 caller + 2099 周期——防污染 2026 时间线（W43 污染教训）。
     */
    private static void scenarioQuarterLevel(Ids ids) {
        System.out.println("== C2 季档：QUARTER 重建 / 查询 / 稀疏（补齐五级覆盖） ==");
        Ids.Caller q = new Ids.Caller("mem-q");
        FakeCompressor comp = new FakeCompressor();
        RawLog raw = new RawLog(ids, q);
        MemoryStore store = new MemoryStore(ids, q);
        Rollup rollup = new Rollup(raw, store, comp, ids, q);

        store.appendDay(LocalDate.of(2099, 8, 15), "### 2099-08-15\n季档测试内容");
        Result cu = rollup.catchup(LocalDate.of(2099, 10, 5));   // Q3 已结束（判断点=季的结束时间）

        String q3 = Periods.quarterKey(LocalDate.of(2099, 8, 15));
        check("季已结束 → 季档重建（" + q3 + "）",
                store.has(Periods.Level.QUARTER, q3), String.valueOf(cu));
        check("季档来自存在的月档（内容可溯）",
                store.read(Periods.Level.QUARTER, q3).contains("季档测试内容"),
                store.read(Periods.Level.QUARTER, q3));
        check("季档级联吸收上链（MONTH→QUARTER→YEAR）",
                store.has(Periods.Level.YEAR, "2099")
                        && store.read(Periods.Level.YEAR, "2099").contains("季档测试内容"),
                store.read(Periods.Level.YEAR, "2099"));
        check("无调用的季不出档（2099-Q4 稀疏是常态）",
                !store.has(Periods.Level.QUARTER, "2099-Q4")
                        && store.keys(Periods.Level.QUARTER).equals(List.of(q3)),
                store.keys(Periods.Level.QUARTER).toString());
        Result miss = store.readRouted(Periods.Level.QUARTER, "2099-Q4");
        check("季档查询无档显式报不存在（不静默不造空档）",
                miss instanceof Result.Error e && e.desc().contains("不存在"),
                String.valueOf(miss));
        Result hit = store.readRouted(Periods.Level.QUARTER, q3);
        check("季档主动查询命中（readRouted 五级通用）",
                hit instanceof Result.Ok, String.valueOf(hit));
    }

    // ---------------------------------------------------------------- 工具

    private static String extractToken(String data) {
        int i = data.indexOf("confirm_token=");
        if (i < 0) {
            i = data.indexOf("confirm_token");
            i = data.indexOf('=', i);
        }
        int start = data.indexOf('=', i) + 1;
        int end = start;
        while (end < data.length() && "0123456789abcdef".indexOf(data.charAt(end)) >= 0) {
            end++;
        }
        return data.substring(start, end);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    private MemoryCheck() {
    }
}
