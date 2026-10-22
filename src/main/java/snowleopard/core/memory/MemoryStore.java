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
import java.util.stream.Stream;

import snowleopard.core.ids.Ids;
import snowleopard.core.result.Result;

/**
 * 压缩记忆链（M5c，2026-10-22 23:12 (UTC+8)，分析 §三.二 / 分析 §8.2）。
 *
 * <p><b>平级五层</b>（{@code memory/days|weeks|months|quarters|years/<键>/memo.md}），
 * 不嵌套成树（ISO 周跨月，见 {@link Periods}）。天档是<b>追加</b>的（每轮一段），
 * 上层档是<b>重建</b>的（周期结束后从下层全量压缩——幂等，省掉"是否已归档"状态）。
 *
 * <p><b>稀疏性</b>：档只在"该期有原始记录"时产生；空档不建、不补、不报缺口。
 *
 * <p><b>默认加载三层</b>（分析 §8.2 + 裁决 7"周日 + 所有未压缩周月日年"）：
 * <ol>
 *   <li>年记忆<b>完整</b>（不许再截断）；</li>
 *   <li>紧邻的已完成天档——<b>稀疏感知</b>：昨天没有就往前找最近一个存在的；</li>
 *   <li>所有<b>未吸收</b>的档（未完结周期的 + 惰性欠账的）。</li>
 * </ol>
 */
public final class MemoryStore {

    /** 读取默认上限（分析 §三.二.4 建议改默认）：默认安全、显式放开（-1 无限）。 */
    public static final int DEFAULT_MAX_CHARS = 8000;

    private final Ids ids;
    private final Ids.Caller caller;
    private int maxChars = DEFAULT_MAX_CHARS;

    public MemoryStore(Ids ids, Ids.Caller caller) {
        this.ids = ids;
        this.caller = caller;
    }

    /** 配置读取上限（-1 无限）。年档不受此限（完整加载）。 */
    public MemoryStore maxChars(int v) {
        this.maxChars = v;
        return this;
    }

    // ------------------------------------------------------------- 写

    /** 天档追加一段（每轮结束压缩一次，分析 §三.二.2）。 */
    public synchronized void appendDay(LocalDate day, String segment) {
        Path f = memoPath(Periods.Level.DAY, Periods.dayKey(day));
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, segment + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("天档追加失败: " + e, e);
        }
    }

    /** 上层档重建写入（覆盖，幂等）。 */
    public synchronized void write(Periods.Level lv, String key, String text) {
        Path f = memoPath(lv, key);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("档写入失败: " + e, e);
        }
    }

    // ------------------------------------------------------------- 读

    public boolean has(Periods.Level lv, String key) {
        return Files.exists(memoPath(lv, key));
    }

    public String read(Periods.Level lv, String key) {
        Path f = memoPath(lv, key);
        try {
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new IllegalStateException("档读取失败: " + e, e);
        }
    }

    /** 现存的档键（升序）。 */
    public List<String> keys(Periods.Level lv) {
        Path dir = ids.mkdirs(caller, "memory/" + dirName(lv));
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("列档失败: " + e, e);
        }
        return out;
    }

    /**
     * 读取路由（草案二、6：按年/季/月/周/日路由）。与 {@link #read} 的区别：
     * 本方法返回 {@code Result}（含截断说明），{@code read} 是原始文本。
     * 上限 {@link #maxChars} 只作用于非年档（年档完整加载是硬约束）。
     */
    public Result readRouted(Periods.Level lv, String key) {
        if (lv == Periods.Level.YEAR) {
            String text = read(lv, key);
            if (text == null) {
                return Result.error("年记忆 " + key + " 不存在（该年无调用则无档——稀疏是常态）");
            }
            return Result.ok(Map.of("level", "year", "key", key, "content", text,
                    "truncated", false));
        }
        String text = read(lv, key);
        if (text == null) {
            return Result.error("记忆 " + lv.wire() + ":" + key + " 不存在（该期无调用则无档——稀疏是常态）");
        }
        boolean truncated = maxChars >= 0 && text.length() > maxChars;
        String shown = truncated ? text.substring(0, maxChars) + "\n…（截断，全文 " + text.length()
                + " 字符；调 maxChars 或 -1 可放开）" : text;
        return Result.ok(Map.of("level", lv.wire(), "key", key, "content", shown,
                "truncated", truncated));
    }

    // ------------------------------------------------------------- 默认加载（分析 §8.2）

    /**
     * 三层默认加载。返回结构化清单（宿主按此装配上下文）。
     *
     * @param today 判定"结束"的基准日（宿主传当前日期；检查可注入）
     */
    public synchronized Map<String, Object> loadDefault(LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        // ① 年记忆完整
        List<Map<String, Object>> years = new ArrayList<>();
        for (String y : keys(Periods.Level.YEAR)) {
            years.add(Map.of("key", y, "content", read(Periods.Level.YEAR, y)));
        }
        out.put("years", years);

        // ② 紧邻的已完成天档（稀疏感知：往前找最近一个存在的，绝不造占位）
        Map<String, Object> nearest = null;
        LocalDate d = today.minusDays(1);
        for (int guard = 0; guard < 3660 && nearest == null; guard++, d = d.minusDays(1)) {
            String k = Periods.dayKey(d);
            if (has(Periods.Level.DAY, k)) {
                nearest = Map.of("key", k, "content", read(Periods.Level.DAY, k));
            }
            if (d.getYear() < today.getYear() - 10) {
                break;
            }
        }
        out.put("nearest_day", nearest == null ? Map.of() : nearest);

        // ③ 所有未吸收的档：未完结周期的 + 惰性欠账（周期已结束但上层档缺失）
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Periods.Level lv : new Periods.Level[]{Periods.Level.DAY, Periods.Level.WEEK,
                Periods.Level.MONTH, Periods.Level.QUARTER}) {
            for (String k : keys(lv)) {
                if (absorbed(lv, k, today)) {
                    continue;
                }
                String text = read(lv, k);
                if (text != null) {
                    pending.add(Map.of("level", lv.wire(), "key", k,
                            "content", limit(text), "backlog", isBacklog(lv, k, today)));
                }
            }
        }
        out.put("unabsorbed", pending);
        return out;
    }

    // ------------------------------------------------------------- 吸收判定

    /**
     * 该档是否已被上层吸收（吸收 = 该期已结束 ∧ 上层档存在且不比它旧）。
     * 年档永远"已在顶层"——由 loadDefault 第①层完整加载。
     */
    public boolean absorbed(Periods.Level lv, String key, LocalDate today) {
        if (lv == Periods.Level.YEAR) {
            return true;
        }
        LocalDate any = anyDayOf(lv, key);
        if (any == null) {
            return true;   // 键解析不了的不参与加载（宁可不载不误载）
        }
        if (!Periods.ended(lv, any, today)) {
            return false;  // 未完结周期：没得吸收
        }
        Periods.Level up = upper(lv);
        if (up == null) {
            return true;
        }
        return has(up, Periods.key(up, any));
    }

    /** 惰性欠账：周期已结束却还没被吸收（归档推迟——分析 §三.二 惰性推论）。 */
    public boolean isBacklog(Periods.Level lv, String key, LocalDate today) {
        LocalDate any = anyDayOf(lv, key);
        return any != null && Periods.ended(lv, any, today) && !absorbed(lv, key, today);
    }

    public static Periods.Level upper(Periods.Level lv) {
        return switch (lv) {
            case DAY -> Periods.Level.WEEK;
            case WEEK -> Periods.Level.MONTH;
            case MONTH -> Periods.Level.QUARTER;
            case QUARTER -> Periods.Level.YEAR;
            case YEAR -> null;
        };
    }

    /**
     * 从档键反推"该期任一天"（用于结束判定）。
     * day=2026-10-22 / week=2026-W43→周一 / month=2026-10→1 日 / quarter=2026-Q4→10-01 / year=2026→01-01。
     */
    public static LocalDate anyDayOf(Periods.Level lv, String key) {
        try {
            return switch (lv) {
                case DAY -> LocalDate.parse(key);
                case WEEK -> {
                    int y = Integer.parseInt(key.substring(0, 4));
                    int w = Integer.parseInt(key.substring(6));
                    // ISO：1 月 4 日必在第 1 周 → 该周周一 + (w-1) 周
                    java.time.LocalDate jan4 = java.time.LocalDate.of(y, 1, 4);
                    yield jan4.with(java.time.DayOfWeek.MONDAY).plusWeeks(w - 1L);
                }
                case MONTH -> LocalDate.parse(key + "-01");
                case QUARTER -> LocalDate.parse(key.substring(0, 4) + "-"
                        + String.format("%02d", (key.charAt(6) - '0' - 1) * 3 + 1) + "-01");
                case YEAR -> LocalDate.parse(key + "-01-01");
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String limit(String text) {
        return maxChars >= 0 && text.length() > maxChars
                ? text.substring(0, maxChars) + "\n…（截断）" : text;
    }

    private Path memoPath(Periods.Level lv, String key) {
        return ids.resolve(caller, "memory/" + dirName(lv) + "/" + key + "/memo.md");
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
