package snowleopard.core.builtin;

import java.time.LocalDate;
import java.util.List;

import snowleopard.core.group.GroupStore;
import snowleopard.core.memory.MemoryStore;
import snowleopard.core.memory.Periods;
import snowleopard.core.wake.WakeStore;

/**
 * 轮末汇总提示（M4，2026-10-23 22:15 (UTC+8)，裁决 6：<b>所有组汇总成一行</b>）。
 *
 * <p>为什么必须汇总：{@code TURN_END} 是所有组共用的注入点（会话/记忆/群组/唤醒都想说话）。
 * 各组各占一行会把尾注撑大，而且"谁后加谁覆盖"——同一注入点必须统一装配。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>只有摘要，没有内容</b>：群组给 {@code gid(n)}、唤醒给次数、记忆给段数——
 *       正文要模型显式调工具取（{@code group_read} 等）；</li>
 *   <li><b>没东西就沉默</b>：三段全空返回 {@code null}，宿主据此不注入（不占上下文）；</li>
 *   <li><b>提示一次</b>：注入后调 {@link #consume}，把"已提示"记进各自状态，
 *       但不推进已读游标——提示与已读是两个维度。</li>
 * </ol>
 */
public final class TurnSummary {

    /** 前缀（宿主可整体替换，但"一行"的形态别破坏）。 */
    public static final String PREFIX = "提醒：";

    private TurnSummary() {
    }

    /**
     * 生成汇总行；无内容返回 {@code null}（= 沉默，不注入）。
     *
     * @param memory 可为 null（宿主没接记忆时空着）
     */
    public static String line(String actor, GroupStore groups, WakeStore wake,
                              MemoryStore memory, LocalDate today) {
        StringBuilder b = new StringBuilder();
        if (groups != null) {
            // 群-8「存在未读自动提示」：只要还有未读，每轮都报（读掉才消失）
            String unread = groups.unreadSummary(actor);
            if (!unread.isEmpty()) {
                b.append("群组未读 ").append(unread);
            }
            // 群-9「新增消息提示」：只在有没提示过的新消息时报一次（与未读是两个维度）
            int fresh = groups.newlyArrived(actor).values().stream()
                    .mapToInt(java.util.List::size).sum();
            if (fresh > 0) {
                if (b.length() > 0) {
                    b.append(" · ");
                }
                b.append("新消息 ").append(fresh).append(" 条");
            }
        }
        if (wake != null) {
            int freshWake = wake.newlyWoken(actor).size();
            if (freshWake > 0) {
                if (b.length() > 0) {
                    b.append(" · ");
                }
                b.append("新唤起 ").append(freshWake).append(" 次");
            }
        }
        if (memory != null) {
            int pending = unabsorbed(memory, today);
            if (pending > 0) {
                if (b.length() > 0) {
                    b.append(" · ");
                }
                b.append("记忆待吸收 ").append(pending).append(" 段");
            }
        }
        return b.length() == 0 ? null : PREFIX + b;
    }

    /**
     * 注入后记账（提示一次）。只标"已提示"，<b>不推进已读游标</b>——
     * 模型可能看完提示没去读，那封消息仍算未读。
     */
    public static void consume(String actor, GroupStore groups, WakeStore wake) {
        if (groups != null) {
            // 只标记本行报过的那些 seq；未读游标（lastReadSeq）不动
            groups.newlyArrived(actor).forEach((gid, seqs) -> groups.markNotified(actor, gid, seqs));
        }
        if (wake != null) {
            List<WakeStore.Request> fresh = wake.newlyWoken(actor);
            wake.markWokenSeen(actor, fresh.stream().map(WakeStore.Request::reqId).toList());
        }
    }

    /** 未吸收的记忆档数（只数 key，不读内容——汇总行不能把上下文撑爆）。 */
    private static int unabsorbed(MemoryStore memory, LocalDate today) {
        int n = 0;
        for (Periods.Level lv : new Periods.Level[]{Periods.Level.DAY, Periods.Level.WEEK,
                Periods.Level.MONTH, Periods.Level.QUARTER}) {
            for (String key : memory.keys(lv)) {
                if (!memory.absorbed(lv, key, today)) {
                    n++;
                }
            }
        }
        return n;
    }
}
