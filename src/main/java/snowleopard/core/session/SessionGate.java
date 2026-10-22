package snowleopard.core.session;

import java.util.LinkedHashMap;
import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 会话管理组（M4，2026-10-22 22:55 (UTC+8)，分析 §三.一，<b>ALWAYS_ON</b>）。
 *
 * <p>三件事：① {@link #yield()} 主动结束本轮（不触发下一次发送）；
 * ② 轮次/消耗双上限，超限<b>强制收束</b>；③ 每轮的用量四数字自动注入
 * （当轮总消耗 / 上句消耗 / 余量 / 轮次消耗）。
 *
 * <p><b>上限口径</b>（星期五 {@code ConfigPanel} 显示 0 的教训）：未配置上限时
 * 余量与轮次显示"无上限"三个字，<b>绝不显示 0</b>——0 会让模型以为没有余量而提前放弃。
 *
 * <p>预算耗尽按 {@code SEVERE} 返回（带伤运行的真用例之一，分析 §8.4）：还能把已产出的
 * 部分结果交出去，但本轮必须收束。
 */
public final class SessionGate {

    /** 双上限。{@code -1} = 无上限。 */
    public record Budget(long maxTurns, long maxTokens) {
        public static final long UNLIMITED = -1L;

        public static Budget unlimited() {
            return new Budget(UNLIMITED, UNLIMITED);
        }

        public static Budget of(long maxTurns, long maxTokens) {
            return new Budget(maxTurns, maxTokens);
        }

        public boolean turnsLimited() {
            return maxTurns >= 0;
        }

        public boolean tokensLimited() {
            return maxTokens >= 0;
        }
    }

    /** 用量快照（注入行与工具返回共用）。 */
    public record Usage(long roundTokens, long lastUtteranceTokens,
                        long totalTokens, long turns) {
    }

    private final Budget budget;
    private long roundTokens;
    private long lastUtteranceTokens;
    private long totalTokens;
    private long turns;
    private boolean yielded;
    private boolean exhausted;
    private String stopReason = "";

    public SessionGate(Budget budget) {
        this.budget = budget == null ? Budget.unlimited() : budget;
    }

    /** 一轮开始（ask 入口）：清当轮计数。跨轮累计不动。 */
    public synchronized void beginRound() {
        roundTokens = 0;
        lastUtteranceTokens = 0;
        yielded = false;
        // exhausted 保持：预算耗尽是会话级事实，不因新一轮复位
    }

    /**
     * 记一次模型调用的消耗。
     *
     * @return 超限 → {@code SEVERE}（部分数据 + "预算耗尽"，本轮强制收束）；否则 {@code OK(Usage)}
     */
    public synchronized Result recordUsage(long inputTokens, long outputTokens) {
        long call = Math.max(0, inputTokens) + Math.max(0, outputTokens);
        lastUtteranceTokens = call;
        roundTokens += call;
        totalTokens += call;
        turns++;
        String over = overBudgetReason();
        if (over != null) {
            exhausted = true;
            stopReason = over;
            return Result.severe(usage(), "预算耗尽（" + over + "），本轮强制收束，不再发起下一次模型调用");
        }
        return Result.ok(usage());
    }

    /**
     * 主动结束本轮（{@code session_yield} 工具的落点）。
     * 之后 {@link #shouldStop()} 为真——主循环据此<b>不再发下一次发送</b>。
     */
    public synchronized Result yield() {
        if (shouldStop()) {
            return Result.warn(usage(), "本轮已在收束状态（" + stopReasonOr() + "），yield 无额外效果");
        }
        yielded = true;
        stopReason = "模型主动 yield";
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("yielded", true);
        d.put("usage", usage());
        d.put("note", "本轮已收束，不再触发下一次发送");
        return Result.ok(d);
    }

    /** 主循环每步都要问：还继续吗？yield 或预算耗尽 → false。 */
    public synchronized boolean shouldStop() {
        return yielded || exhausted;
    }

    public synchronized Usage usage() {
        return new Usage(roundTokens, lastUtteranceTokens, totalTokens, turns);
    }

    /** 收束原因（空 = 未收束）。 */
    public synchronized String stopReason() {
        return shouldStop() ? stopReasonOr() : "";
    }

    /**
     * 自动注入行（四个数字，规范 分析 §三.一.3）。
     *
     * <pre>
     * 会话用量: 当轮消耗 1200 tok · 上句消耗 800 tok · 余量 8800 tok（上限 10000）· 轮次 3（上限 10）
     * 会话用量: 当轮消耗 1200 tok · 上句消耗 800 tok · 余量 无上限 · 轮次 3（无上限）
     * </pre>
     */
    public synchronized String injectionLine() {
        Usage u = usage();
        String remaining = budget.tokensLimited()
                ? Math.max(0, budget.maxTokens() - u.totalTokens()) + " tok（上限 " + budget.maxTokens() + "）"
                : "无上限";
        String turnsPart = budget.turnsLimited()
                ? u.turns() + "（上限 " + budget.maxTurns() + "）"
                : u.turns() + "（无上限）";
        return "会话用量: 当轮消耗 " + u.roundTokens() + " tok · 上句消耗 " + u.lastUtteranceTokens()
                + " tok · 余量 " + remaining + " · 轮次 " + turnsPart;
    }

    private String overBudgetReason() {
        Usage u = usage();
        if (budget.turnsLimited() && u.turns() > budget.maxTurns()) {
            return "轮次超限（" + u.turns() + "/" + budget.maxTurns() + "）";
        }
        if (budget.tokensLimited() && u.totalTokens() > budget.maxTokens()) {
            return "消耗超限（" + u.totalTokens() + "/" + budget.maxTokens() + " tok）";
        }
        return null;
    }

    private String stopReasonOr() {
        return stopReason == null || stopReason.isEmpty() ? "已收束" : stopReason;
    }
}
