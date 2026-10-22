package snowleopard.core.trigger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 触发分发（M3，2026-10-22 22:46 (UTC+8)，分析 §二.七）。
 *
 * <p>四类触发点全部落在主循环上：{@code onSessionStart/End}、{@code onTurnStart/End}
 * （轮 = 一次模型请求的完整往返）。工具组声明 {@code triggers: ["turn_end"]} 注册进分发表。
 *
 * <p><b>主动唤起必须有回应，被动触发可选</b>（草案二、2）：被动建议默认必须返回、可显式
 * {@code silent}——完全可选会导致触发器静默吞错。触发器异常按分级记录并继续
 * （内置 WARN/ERROR、模组可 SEVERE——不能让一个组的坏钩子杀掉整轮）。
 *
 * <p><b>执行序写死</b>：同一触发点多组响应按 {@code (priority, 组全名)} 确定序；
 * 不写死顺序 = 重名问题的翻版（这次是执行序）。
 */
public final class TriggerRegistry {

    public enum Point {
        SESSION_START, SESSION_END, TURN_START, TURN_END
    }

    /** 触发器钩子。{@code silent} 时失败仍记 WARN/ERROR 但不进结果流。 */
    public interface Hook {
        String groupFull();

        /** 小者先执行（默认 0）。同 priority 按组全名字典序。 */
        default int priority() {
            return 0;
        }

        default boolean silent() {
            return false;
        }

        /** 执行一次触发。异常由分发器兜——坏钩子不杀整轮。 */
        void fire(Point point);
    }

    /** 单次触发的记录（审计/结果流用）。 */
    public record Hit(String groupFull, Point point, Result result, boolean silent) {
    }

    private final Map<Point, List<Hook>> hooks = new LinkedHashMap<>();

    /** 注册钩子。 */
    public void register(Hook hook) {
        for (Point p : Point.values()) {
            hooks.computeIfAbsent(p, k -> new ArrayList<>()).add(hook);
        }
    }

    /** 按声明的触发点注册（更精确的形态；triggers 清单字段的落点）。 */
    public void register(Point[] points, Hook hook) {
        for (Point p : points) {
            hooks.computeIfAbsent(p, k -> new ArrayList<>()).add(hook);
        }
    }

    /**
     * 分发一个触发点：确定序执行，异常按 {@link Result#severe} 或 {@link Result#warn}
     * 记录并继续。返回本次全部 {@link Hit}（含 silent 的，调用方自行过滤）。
     */
    public List<Hit> fire(Point point) {
        List<Hook> list = new ArrayList<>(hooks.getOrDefault(point, List.of()));
        list.sort(Comparator.comparingInt(Hook::priority)
                .thenComparing(Hook::groupFull));
        List<Hit> out = new ArrayList<>();
        for (Hook h : list) {
            Result r;
            try {
                h.fire(point);
                r = Result.ok(null);
            } catch (RuntimeException e) {
                // 内置组 WARN/ERROR 语义、模组组可 SEVERE：这里统一按"带伤运行"记，
                // 区别只在描述里——不猜组的来源分级（来源在 Hit.groupFull 里，审计可判）
                r = Result.severe(null, "触发器异常（" + h.groupFull() + "@" + point + "）: "
                        + e.getMessage());
            }
            out.add(new Hit(h.groupFull(), point, r, h.silent()));
        }
        return out;
    }

    /** 确定序清单（启动报告/检查用）：每个触发点的执行序。 */
    public Map<Point, List<String>> order() {
        Map<Point, List<String>> out = new LinkedHashMap<>();
        for (Point p : Point.values()) {
            List<Hook> list = new ArrayList<>(hooks.getOrDefault(p, List.of()));
            list.sort(Comparator.comparingInt(Hook::priority).thenComparing(Hook::groupFull));
            out.put(p, list.stream().map(Hook::groupFull).toList());
        }
        return out;
    }
}
