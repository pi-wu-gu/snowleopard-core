package snowleopard.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.ids.Ids;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

/**
 * 轮压缩 + 惰性归档 + 完整性修复（M5b/M5c/M5d，2026-10-22 23:12 (UTC+8)）。
 *
 * <p><b>压缩流</b>：轮结束 → 压缩该轮追加到天档（分析 §三.二.2）→ 惰性 catchup
 * （已结束周期的上层档幂等重建）。天档追加、上层重建——重建免掉"是否已归档"状态，
 * 重复跑不产生重复内容。
 *
 * <p><b>惰性</b>（分析 §三.二 推论）：周期翻转的归档挂在轮末检测上，无调用日没有触发时机——
 * 跨过空周末/空月末，归档<b>推迟</b>到下一次轮末或启动 catchup，不需要墙钟定时器。
 *
 * <p><b>冷却补做</b>（裁决 6）：压缩失败抛异常 → 丢弃当次产物、记 WARN、目标进冷却
 * （{@link #COOLDOWN_MS}），冷却内不重试同一目标，之后自动补。原始记录永远在。
 *
 * <p><b>完整性修复</b>（裁决 6 的手动兜底）：判据是"<b>原始在、压缩缺</b>"而不是
 * "日历有洞"——把空档当缺口会造出"压缩了空内容"的假记忆（分析 §10.5 最危险的一条）。
 */
public final class Rollup {

    /** 压缩失败后的冷却时长（毫秒）。 */
    public static final long COOLDOWN_MS = 5 * 60 * 1000L;

    private final RawLog raw;
    private final MemoryStore store;
    private final Compressor compressor;
    private final Ids ids;
    private final Ids.Caller caller;

    public Rollup(RawLog raw, MemoryStore store, Compressor compressor, Ids ids, Ids.Caller caller) {
        this.raw = raw;
        this.store = store;
        this.compressor = compressor;
        this.ids = ids;
        this.caller = caller;
    }

    /**
     * 轮结束：压缩该轮 → 追加天档 → 惰性 catchup。
     *
     * @return OK/WARN（压缩失败冷却）/SEVERE（catchup 后仍有欠账）
     */
    public synchronized Result onRoundEnd(String roundKey, LocalDate today) {
        List<RawLog.Round> rounds = raw.listRounds();
        RawLog.Round round = null;
        for (RawLog.Round r : rounds) {
            if (r.key().equals(roundKey)) {
                round = r;
                break;
            }
        }
        if (round == null) {
            return Result.error("轮不存在: " + roundKey);
        }
        if (round.entries() == 0) {
            return Result.warn(Map.of("round", roundKey), "轮 " + roundKey + " 没有条目——不产生压缩（稀疏约束）");
        }
        LocalDate day = RawLog.dayOf(round.endedTs());
        String target = "day:" + day;
        if (inCooldown(target)) {
            return Result.warn(Map.of("round", roundKey, "cooldown", target),
                    "目标 " + target + " 在冷却中（上次压缩失败）——稍后自动补做");
        }
        String source = renderRound(round);
        String segment;
        try {
            segment = compressor.compress("round→day", source);
        } catch (RuntimeException e) {
            noteFailure(target, e.getMessage());
            return Result.warn(Map.of("round", roundKey),
                    "本轮压缩失败已丢弃（原始记录仍在，冷却后补做）: " + e.getMessage());
        }
        store.appendDay(day, "### round " + roundKey + "\n" + segment + "\n");
        clearFailure(target);

        Result cu = catchup(today);
        if (cu instanceof Result.Severe) {
            return cu;
        }
        return Result.ok(Map.of("round", roundKey, "day", day.toString(),
                "appended", true));
    }

    /**
     * 惰性归档：所有<b>已结束</b>周期的上层档幂等重建（下层有更新才重建）。
     * 未结束周期不动；冷却中的目标跳过。
     */
    public synchronized Result catchup(LocalDate today) {
        List<String> rebuilt = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Periods.Level[] chain = {Periods.Level.DAY, Periods.Level.WEEK,
                Periods.Level.MONTH, Periods.Level.QUARTER};
        for (Periods.Level lv : chain) {
            Periods.Level up = MemoryStore.upper(lv);
            for (String k : store.keys(lv)) {
                LocalDate any = MemoryStore.anyDayOf(lv, k);
                if (any == null || !Periods.ended(lv, any, today)) {
                    continue;   // 未结束：该层还在长，不动
                }
                String target = up.wire() + ":" + Periods.key(up, any);
                if (inCooldown(target)) {
                    skipped.add(target);
                    continue;
                }
                if (store.has(up, Periods.key(up, any)) && !stale(lv, k, up, Periods.key(up, any))) {
                    continue;   // 上层已包含最新下层
                }
                String merged = mergeLower(lv, any, today);
                if (merged.isEmpty()) {
                    continue;   // 该期什么都没有（不该发生，防一手）
                }
                try {
                    String summary = compressor.compress(lv.wire() + "s→" + up.wire(), merged);
                    store.write(up, Periods.key(up, any), summary);
                    clearFailure(target);
                    rebuilt.add(target);
                } catch (RuntimeException e) {
                    noteFailure(target, e.getMessage());
                    skipped.add(target + "（失败冷却: " + e.getMessage() + "）");
                }
            }
        }
        if (skipped.isEmpty()) {
            return Result.ok(Map.of("rebuilt", rebuilt));
        }
        return Result.severe(Map.of("rebuilt", rebuilt, "skipped", skipped),
                "惰性归档有 " + skipped.size() + " 处未完成（含冷却中的失败）——原始记录仍在，可稍后重试或跑 memory_repair");
    }

    /**
     * 完整性修复（手动兜底，草案记忆组 9）：判据"<b>原始在、压缩缺</b>"。
     * 空档（无原始）<b>永远不算缺口</b>。返回修了什么 / 剩什么（剩缺口 → SEVERE）。
     */
    public synchronized Result repair(LocalDate today) {
        List<String> repaired = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        // ① 有轮（原始在）但天档缺 → 从原始重建天档
        for (RawLog.Round r : raw.listRounds()) {
            LocalDate day = RawLog.dayOf(r.endedTs());
            String dk = Periods.dayKey(day);
            if (store.has(Periods.Level.DAY, dk)) {
                continue;
            }
            String target = "day:" + dk;
            if (inCooldown(target)) {
                gaps.add(target + "（冷却中）");
                continue;
            }
            try {
                String summary = compressor.compress("rounds→day", renderDay(day));
                store.write(Periods.Level.DAY, dk, summary);
                clearFailure(target);
                repaired.add(target);
            } catch (RuntimeException e) {
                noteFailure(target, e.getMessage());
                gaps.add(target + "（失败: " + e.getMessage() + "）");
            }
        }

        // ② 上层缺（下层在、周期已结束）→ catchup 处理（幂等重建）
        Result cu = catchup(today);
        if (cu instanceof Result.Severe sev) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) sev.data();
            Object sk = data.get("skipped");
            if (sk instanceof List<?> l) {
                for (Object o : l) {
                    gaps.add(String.valueOf(o));
                }
            }
        }
        // 空档（无原始、无档）不是缺口——稀疏性硬约束，这里故意不报

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repaired", repaired);
        out.put("gaps_remaining", gaps);
        out.put("note", "判据=原始在、压缩缺；空档（无调用）永远不算缺口");
        if (!gaps.isEmpty()) {
            return Result.severe(out, "修复后仍有 " + gaps.size() + " 处缺口（多为压缩失败冷却中）——原始记录都在");
        }
        return Result.ok(out);
    }

    // ------------------------------------------------------------- 冷却状态

    private Path failuresFile() {
        return ids.mkdirs(caller, "memory").resolve(".rollup-failures.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFailures() {
        Path f = failuresFile();
        if (!Files.exists(f)) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveFailures(Map<String, Object> m) {
        try {
            Files.writeString(failuresFile(), Json.dump(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("冷却状态写盘失败: " + e, e);
        }
    }

    private boolean inCooldown(String target) {
        Map<String, Object> f = loadFailures();
        Object o = f.get(target);
        if (!(o instanceof Map)) {
            return false;
        }
        Object ts = ((Map<String, Object>) o).get("failedTs");
        return ts instanceof Number n
                && System.currentTimeMillis() - n.longValue() < COOLDOWN_MS;
    }

    private void noteFailure(String target, String reason) {
        Map<String, Object> f = loadFailures();
        f.put(target, Map.of("failedTs", System.currentTimeMillis(),
                "reason", String.valueOf(reason)));
        saveFailures(f);
    }

    private void clearFailure(String target) {
        Map<String, Object> f = loadFailures();
        if (f.remove(target) != null) {
            saveFailures(f);
        }
    }

    // ------------------------------------------------------------- 渲染

    private String renderRound(RawLog.Round round) {
        StringBuilder b = new StringBuilder();
        for (RawLog.Entry e : raw.readRound(round.key())) {
            b.append('[').append(e.seq()).append("] ").append(e.ts()).append(' ')
                    .append(e.kind().wire()).append(": ").append(e.payload()).append('\n');
        }
        return b.toString();
    }

    private String renderDay(LocalDate day) {
        StringBuilder b = new StringBuilder();
        for (RawLog.Round r : raw.roundsEndingOn(day)) {
            b.append("== round ").append(r.key()).append(" ==\n").append(renderRound(r));
        }
        return b.toString();
    }

    /** 合并该期所有下层档（重建上层用）。只并<b>存在的</b>档——缺的天不是待补项。 */
    private String mergeLower(Periods.Level lower, LocalDate anyOfPeriod, LocalDate today) {
        StringBuilder b = new StringBuilder();
        for (String k : store.keys(lower)) {
            LocalDate d = MemoryStore.anyDayOf(lower, k);
            if (d == null || !Periods.samePeriod(nextUpper(lower), d, anyOfPeriod)) {
                continue;
            }
            String text = store.read(lower, k);
            if (text != null && !text.isBlank()) {
                b.append("== ").append(lower.wire()).append(' ').append(k).append(" ==\n")
                        .append(text).append('\n');
            }
        }
        return b.toString();
    }

    /** lower 层归并目标的层（DAY→WEEK 等，即 {@link MemoryStore#upper}）。 */
    private static Periods.Level nextUpper(Periods.Level lower) {
        return MemoryStore.upper(lower);
    }

    /** 下层比上层新（mtime）→ 上层需要重建。 */
    private boolean stale(Periods.Level lower, String lowerKey,
                          Periods.Level up, String upKey) {
        try {
            Path lp = ids.resolve(caller, "memory/" + dirName(lower) + "/" + lowerKey + "/memo.md");
            Path upPath = ids.resolve(caller, "memory/" + dirName(up) + "/" + upKey + "/memo.md");
            if (!Files.exists(upPath)) {
                return true;
            }
            return Files.getLastModifiedTime(lp).toMillis()
                    > Files.getLastModifiedTime(upPath).toMillis();
        } catch (IOException e) {
            return true;   // 看不清就重建——重建幂等，宁可多做
        }
    }

    private static String dirName(Periods.Level lv) {
        return switch (lv) {
            case DAY -> "days";
            case WEEK -> "weeks";
            case MONTH -> "months";
            case QUARTER -> "quarters";
            case YEAR -> "years";
        };
    }
}
