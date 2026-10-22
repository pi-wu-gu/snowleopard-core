package snowleopard.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import snowleopard.core.ids.Ids;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

/**
 * 轮次原始记录（M5a，2026-10-22 23:12 (UTC+8)，分析 §三.二.2）。
 *
 * <p>双根之一：<b>与压缩记忆不共用根</b>（{@code memory-raw/} vs {@code memory/}——防误删）。
 * 每轮一个 JSONL 文件 {@code round-<结束时间戳>.jsonl}，每行一个结构化条目：
 * <pre>{seq, ts, kind: thought|tool_call, payload}</pre>
 * 工具调用条目<b>不含完整输出</b>（草案二、2），但含完整时间戳。{@code seq} 即次路由
 * （单次思考及单次调用，分析 §8.1 裁决）——因此原始记录必须是结构化行而不是纯文本。
 *
 * <p><b>稀疏性硬约束</b>（21:28）：原始记忆必须调用过才产生——无调用日没有轮文件，
 * 这是<b>正常状态</b>而非缺口。一切压缩/补做/修复以"有原始才算存在"为第一判据。
 *
 * <p>落盘挂调用 ID 的家（{@code ids/<ID>/memory-raw/}），ID 间隔离。
 */
public final class RawLog {

    /** 条目类型。 */
    public enum Kind {
        THOUGHT, TOOL_CALL;

        public String wire() {
            return this == THOUGHT ? "thought" : "tool_call";
        }

        public static Kind of(String wire) {
            return "tool_call".equals(wire) ? TOOL_CALL : THOUGHT;
        }
    }

    /** 一条原始记录。 */
    public record Entry(int seq, String ts, Kind kind, String payload) {
    }

    /** 一轮的元信息（文件名即结束时间戳）。 */
    public record Round(String key, long endedTs, int entries) {
    }

    private static final DateTimeFormatter KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Ids ids;
    private final Ids.Caller caller;

    public RawLog(Ids ids, Ids.Caller caller) {
        this.ids = ids;
        this.caller = caller;
    }

    /** 开一轮新记录（文件按结束时间命名——轮结束后定稿，见 {@link #closeRound}）。 */
    public RoundWriter openRound(long endedTs) {
        return new RoundWriter(keyOf(endedTs), endedTs);
    }

    /** 一轮的写入器：append 条目，close 时落盘。 */
    public final class RoundWriter {
        private final String key;
        private final long endedTs;
        private final List<Entry> entries = new ArrayList<>();

        private RoundWriter(String key, long endedTs) {
            this.key = key;
            this.endedTs = endedTs;
        }

        public RoundWriter add(Kind kind, String payload) {
            entries.add(new Entry(entries.size() + 1, ts(System.currentTimeMillis()), kind,
                    payload == null ? "" : payload));
            return this;
        }

        /** 定稿落盘（幂等：同 key 重写覆盖）。 */
        public Round closeRound() {
            Path dir = ids.mkdirs(caller, "memory-raw");
            Path f = dir.resolve("round-" + key + ".jsonl");
            StringBuilder b = new StringBuilder();
            for (Entry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", e.seq());
                m.put("ts", e.ts());
                m.put("kind", e.kind().wire());
                m.put("payload", e.payload());
                b.append(Json.dump(m)).append('\n');
            }
            try {
                Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("轮原始记录写盘失败: " + e, e);
            }
            return new Round(key, endedTs, entries.size());
        }
    }

    /** 列出全部轮（按时间升序）。无调用 = 无轮 = 空列表（不是错误）。 */
    public List<Round> listRounds() {
        Path dir = ids.mkdirs(caller, "memory-raw");
        List<Round> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith("round-"))
                    .sorted()
                    .forEach(p -> out.add(new Round(
                            p.getFileName().toString()
                                    .replaceFirst("^round-", "").replaceFirst("\\.jsonl$", ""),
                            parseKey(p.getFileName().toString()),
                            countLines(p))));
        } catch (IOException e) {
            throw new IllegalStateException("读取轮列表失败: " + e, e);
        }
        return out;
    }

    /** 读一轮的全部条目。 */
    public List<Entry> readRound(String key) {
        Path f = ids.resolve(caller, "memory-raw/round-" + key + ".jsonl");
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> m = Json.parseObject(line);
                out.add(new Entry(
                        (int) asLong(m.get("seq")),
                        String.valueOf(m.getOrDefault("ts", "")),
                        Kind.of(String.valueOf(m.getOrDefault("kind", "thought"))),
                        String.valueOf(m.getOrDefault("payload", ""))));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取轮记录失败 " + key + ": " + e, e);
        }
        return out;
    }

    /** 次路由：读轮内单条（分析 §8.1：单次思考及调用，seq 寻址）。 */
    public Result readEntry(String roundKey, int seq) {
        for (Entry e : readRound(roundKey)) {
            if (e.seq() == seq) {
                return Result.ok(e);
            }
        }
        return Result.error("轮 " + roundKey + " 内没有 seq=" + seq + " 的条目");
    }

    /** 按结束日期收轮（归桶按结束时间，分析 §三.二.1）。 */
    public List<Round> roundsEndingOn(LocalDate day) {
        List<Round> out = new ArrayList<>();
        for (Round r : listRounds()) {
            if (dayOf(r.endedTs()).equals(day)) {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- 时间键

    /** 轮文件键（结束时间戳）。 */
    public static String keyOf(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.ofHours(8)).format(KEY);
    }

    public static long parseKey(String fileOrKey) {
        String k = fileOrKey.replaceFirst("^round-", "").replaceFirst("\\.jsonl$", "");
        try {
            return OffsetDateTime.parse(k + "+08:00", DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssXXX"))
                    .toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public static LocalDate dayOf(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.ofHours(8)).toLocalDate();
    }

    public static String ts(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.ofHours(8)).format(TS);
    }

    private static int countLines(Path p) {
        try {
            int n = 0;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
