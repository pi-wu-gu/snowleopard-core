package snowleopard.core.memory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;

/**
 * 周期键与结束判定（M5c，2026-10-22 23:12 (UTC+8)，分析 §三.二.1）。
 *
 * <p><b>平级五层</b>：{@code days/<D>} {@code weeks/<W>} {@code months/<M>}
 * {@code quarters/<Q>} {@code years/<Y>}——各层独立寻址，<b>不嵌套成一棵树</b>：
 * ISO 周跨月/跨年（W44 末在 11 月、周中的天在 10 月），单路径必与"归桶按结束时间"冲突。
 *
 * <p>周用 <b>ISO 周</b>（{@code 2026-W43}），季用<b>自然季度</b>（{@code 2026-Q4}）——
 * 两套日历混用是这组最容易埋的坑，所以全部收在本类，别处不再自己算。
 */
public final class Periods {

    /** 五层。 */
    public enum Level {
        DAY, WEEK, MONTH, QUARTER, YEAR;

        public String wire() {
            return switch (this) {
                case DAY -> "day";
                case WEEK -> "week";
                case MONTH -> "month";
                case QUARTER -> "quarter";
                case YEAR -> "year";
            };
        }

        public static Level of(String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "day", "days", "日" -> DAY;
                case "week", "weeks", "周" -> WEEK;
                case "month", "months", "月" -> MONTH;
                case "quarter", "quarters", "季" -> QUARTER;
                case "year", "years", "年" -> YEAR;
                default -> throw new IllegalArgumentException("未知记忆层级: " + s);
            };
        }
    }

    private Periods() {
    }

    // ------------------------------------------------------------- 键

    public static String dayKey(LocalDate d) {
        return d.toString();   // 2026-10-22
    }

    public static String weekKey(LocalDate d) {
        int w = d.get(WeekFields.ISO.weekOfWeekBasedYear());
        int y = d.get(WeekFields.ISO.weekBasedYear());   // ISO 年：跨年周归属正确
        return String.format("%d-W%02d", y, w);
    }

    public static String monthKey(LocalDate d) {
        return String.format("%d-%02d", d.getYear(), d.getMonthValue());
    }

    public static String quarterKey(LocalDate d) {
        int q = (d.getMonthValue() - 1) / 3 + 1;
        return d.getYear() + "-Q" + q;
    }

    public static String yearKey(LocalDate d) {
        return String.valueOf(d.getYear());
    }

    public static String key(Level lv, LocalDate d) {
        return switch (lv) {
            case DAY -> dayKey(d);
            case WEEK -> weekKey(d);
            case MONTH -> monthKey(d);
            case QUARTER -> quarterKey(d);
            case YEAR -> yearKey(d);
        };
    }

    // ------------------------------------------------------------- 结束判定（惰性归档的依据）

    /** 该天已结束（今天之后的日子算"还没到"，今天算"未结束"）。 */
    public static boolean dayEnded(LocalDate day, LocalDate today) {
        return day.isBefore(today);
    }

    /** 该 ISO 周已结束：周的最后一天（周日）早于今天。 */
    public static boolean weekEnded(LocalDate weekAnyDay, LocalDate today) {
        return endOfWeek(weekAnyDay).isBefore(today);
    }

    public static boolean monthEnded(LocalDate monthAnyDay, LocalDate today) {
        return monthAnyDay.withDayOfMonth(monthAnyDay.lengthOfMonth()).isBefore(today);
    }

    public static boolean quarterEnded(LocalDate qAnyDay, LocalDate today) {
        return endOfQuarter(qAnyDay).isBefore(today);
    }

    public static boolean yearEnded(LocalDate yearAnyDay, LocalDate today) {
        return yearAnyDay.withDayOfYear(yearAnyDay.lengthOfYear()).isBefore(today);
    }

    public static boolean ended(Level lv, LocalDate day, LocalDate today) {
        return switch (lv) {
            case DAY -> dayEnded(day, today);
            case WEEK -> weekEnded(day, today);
            case MONTH -> monthEnded(day, today);
            case QUARTER -> quarterEnded(day, today);
            case YEAR -> yearEnded(day, today);
        };
    }

    // ------------------------------------------------------------- 周期边界

    /** ISO 周的周一。 */
    public static LocalDate startOfWeek(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    /** ISO 周的周日（裁决 7："周期翻转取周日"——紧邻档从这天找）。 */
    public static LocalDate endOfWeek(LocalDate d) {
        return d.with(DayOfWeek.SUNDAY);
    }

    public static LocalDate startOfMonth(LocalDate d) {
        return d.withDayOfMonth(1);
    }

    public static LocalDate endOfMonth(LocalDate d) {
        return d.withDayOfMonth(d.lengthOfMonth());
    }

    public static LocalDate startOfQuarter(LocalDate d) {
        int q = (d.getMonthValue() - 1) / 3;
        return LocalDate.of(d.getYear(), q * 3 + 1, 1);
    }

    public static LocalDate endOfQuarter(LocalDate d) {
        LocalDate s = startOfQuarter(d);
        return s.plusMonths(2).withDayOfMonth(s.plusMonths(2).lengthOfMonth());
    }

    /** 该层"一期"覆盖的天（用于把天档归进周/月/季/年）。 */
    public static boolean samePeriod(Level lv, LocalDate a, LocalDate b) {
        return key(lv, a).equals(key(lv, b));
    }
}
