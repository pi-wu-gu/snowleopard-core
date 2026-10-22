package snowleopard.core.check;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import snowleopard.core.ids.Ids;
import snowleopard.core.notes.NoteStore;
import snowleopard.core.result.Result;
import snowleopard.core.session.SessionGate;
import snowleopard.core.todo.TodoStore;

/**
 * M4 验收（2026-10-22 22:55 (UTC+8)）：会话管理 + 任务清单 + 笔记三组。
 *
 * <p>对应开发流程 M4 的验收点：yield 后主循环确实不再发下一轮；预算超限自动收束；
 * 笔记删除无 token 不生效、token 与内容不匹配被拒。外加三组的删除语义与注入行口径。
 */
public final class WorkCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sl-work-check");
        Ids ids = Ids.at(root);
        Ids.Caller caller = new Ids.Caller("id0");

        scenarioYieldStopsLoop(ids, caller);
        scenarioBudget(ids, caller);
        scenarioInjection(ids, caller);
        scenarioTodo(ids, caller);
        scenarioNotes(ids, caller);

        System.out.println("PASS work: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 会话

    private static void scenarioYieldStopsLoop(Ids ids, Ids.Caller caller) {
        System.out.println("== A yield 后主循环不再发下一轮 ==");
        SessionGate gate = new SessionGate(SessionGate.Budget.unlimited());
        gate.beginRound();
        check("未 yield 时循环继续", !gate.shouldStop(), gate.stopReason());

        // 模拟主循环：每步先问 shouldStop，再"发下一次发送"
        int sends = 0;
        for (int i = 0; i < 5; i++) {
            if (gate.shouldStop()) {
                break;
            }
            sends++;                       // 计作"一次发送"
            gate.recordUsage(10, 20);
            if (i == 1) {
                Result y = gate.yield();   // 第二步后主动收束
                check("yield 返回 OK + 说明", y instanceof Result.Ok
                        && String.valueOf(((Result.Ok) y).data()).contains("收束"), y.level());
            }
        }
        check("yield 后循环停在第 2 次发送（没有发第 3 次）", sends == 2, "sends=" + sends);
        check("shouldStop 为真且原因是 yield", gate.shouldStop()
                && gate.stopReason().contains("yield"), gate.stopReason());

        // 重复 yield 不该假装又收束一次
        Result again = gate.yield();
        check("重复 yield 是 WARN 不是二次收束", again instanceof Result.Warn, again.level());
    }

    private static void scenarioBudget(Ids ids, Ids.Caller caller) {
        System.out.println("== B 预算超限自动收束（SEVERE 带伤运行）==");
        SessionGate gate = new SessionGate(SessionGate.Budget.of(3, 100));
        gate.beginRound();
        check("预算内第 1 次 OK", gate.recordUsage(30, 20) instanceof Result.Ok, "");
        check("预算内第 2 次 OK", gate.recordUsage(30, 20) instanceof Result.Ok, "");
        check("未超限时不收束", !gate.shouldStop(), "");
        Result third = gate.recordUsage(30, 20);   // 累计 150 > 100
        check("消耗超限返回 SEVERE（部分数据+描述）", third instanceof Result.Severe
                && ((Result.Severe) third).data() != null, third.level());
        check("超限后强制收束", gate.shouldStop() && gate.stopReason().contains("超限"),
                gate.stopReason());

        SessionGate turnGate = new SessionGate(SessionGate.Budget.of(1, -1));
        turnGate.beginRound();
        turnGate.recordUsage(1, 1);
        Result over = turnGate.recordUsage(1, 1);   // 第 2 次 > maxTurns=1
        check("轮次超限同样 SEVERE 收束", over instanceof Result.Severe
                && turnGate.shouldStop(), over.level());
    }

    private static void scenarioInjection(Ids ids, Ids.Caller caller) {
        System.out.println("== C 注入行四数字 + 无上限显示「无上限」不是 0 ==");
        SessionGate gate = new SessionGate(SessionGate.Budget.of(10, 1000));
        gate.beginRound();
        gate.recordUsage(300, 200);   // 500
        gate.recordUsage(100, 100);   // 200，当轮累计 700
        String line = gate.injectionLine();
        check("含当轮消耗 700", line.contains("当轮消耗 700"), line);
        check("含上句消耗 200", line.contains("上句消耗 200"), line);
        check("含余量 300（1000-700）", line.contains("余量 300"), line);
        check("含轮次 2（上限 10）", line.contains("轮次 2（上限 10）"), line);

        SessionGate free = new SessionGate(SessionGate.Budget.unlimited());
        free.beginRound();
        free.recordUsage(1, 1);
        String f = free.injectionLine();
        check("无上限显示「无上限」", f.contains("余量 无上限") && f.contains("无上限"), f);
        check("无上限不出现「余量 0」", !f.contains("余量 0"), f);
    }

    // ---------------------------------------------------------------- 任务

    private static void scenarioTodo(Ids ids, Ids.Caller caller) {
        System.out.println("== D 任务清单：状态机 / 一层子条目 / 删除语义 ==");
        TodoStore todo = new TodoStore(ids, caller);
        Result a = todo.add("写报告");
        String idA = ((TodoStore.Item) ((Result.Ok) a).data()).id();
        Result b = todo.addSub(idA, "查资料");
        String idB = ((TodoStore.Item) ((Result.Ok) b).data()).id();
        check("父 + 一层子条目可建", todo.list().size() == 2, "size=" + todo.list().size());

        Result deep = todo.addSub(idB, "再往下");
        check("二层子条目被拒（限一层）", deep instanceof Result.Error
                && ((Result.Error) deep).desc().contains("一层"), deep.level());

        check("标记 DONE", todo.mark(idB, TodoStore.Status.DONE) instanceof Result.Ok, "");
        check("标记 BLOCKED", todo.mark(idA, TodoStore.Status.BLOCKED) instanceof Result.Ok, "");
        check("DELETED 不能经 mark 设置",
                todo.mark(idA, TodoStore.Status.DELETED) instanceof Result.Error, "");

        // delete = 标记不删，父删子随之标记
        check("delete 返回标记清单", todo.delete(idA) instanceof Result.Ok, "");
        check("delete 后 list 不出现（标记删除）", todo.list().isEmpty(), "list=" + todo.list().size());
        check("但盘上仍在（size 含标记删除）", todo.size() == 2, "size=" + todo.size());
        check("子条目随之标记删除", !todo.list().stream()
                .anyMatch(i -> i.id().equals(idB)), "");

        // 恢复 + purge
        check("可恢复（mark PENDING）", todo.mark(idA, TodoStore.Status.PENDING) instanceof Result.Ok, "");
        check("purge 物理删（级联子条目）", todo.purge(idA) instanceof Result.Ok, "");
        check("purge 后盘上也消失", todo.size() == 0, "size=" + todo.size());

        // 落盘
        check("落盘到 ID 家 todo/", Files.exists(ids.resolve(caller, "todo/todo.json")), "");
    }

    // ---------------------------------------------------------------- 笔记

    private static void scenarioNotes(Ids ids, Ids.Caller caller) {
        System.out.println("== E 笔记：两段式删除 + 回收站 + 搜索 ==");
        NoteStore notes = new NoteStore(ids, caller);
        check("建夹", notes.mkdir("proj") instanceof Result.Ok, "");
        check("建文", notes.write("proj/a.txt", "第一版内容") instanceof Result.Ok, "");
        check("读回", String.valueOf(((Result.Ok) notes.read("proj/a.txt")).data())
                .contains("第一版内容"), "");
        check("追加", notes.append("proj/a.txt", "\n第二行") instanceof Result.Ok, "");
        check("追加后两行都在", String.valueOf(((Result.Ok) notes.read("proj/a.txt")).data())
                .contains("第二行"), "");

        // 两段式删除：无 token 不生效
        Result step1 = notes.delete("proj/a.txt", null);
        check("首段返回 Ok + confirm_token", step1 instanceof Result.Ok
                && String.valueOf(((Result.Ok) step1).data()).contains("confirm_token"), step1.level());
        check("首段之后文件仍在（未执行删除）",
                Files.exists(ids.resolve(caller, "notes/proj/a.txt")), "");
        String token = extractToken(String.valueOf(((Result.Ok) step1).data()));

        // 内容变化 → 旧 token 失效
        notes.append("proj/a.txt", "\n第三行（改变内容）");
        Result stale = notes.delete("proj/a.txt", token);
        check("token 与内容不匹配被拒", stale instanceof Result.Error
                && ((Result.Error) stale).desc().contains("已变化"), stale.level());
        check("被拒后文件仍在", Files.exists(ids.resolve(caller, "notes/proj/a.txt")), "");

        // 正确流程：拿新 token → 二次调用 → 移入回收站
        Result step1b = notes.delete("proj/a.txt", null);
        String token2 = extractToken(String.valueOf(((Result.Ok) step1b).data()));
        Result step2 = notes.delete("proj/a.txt", token2);
        check("带 token 二次调用执行删除", step2 instanceof Result.Ok, step2.level());
        check("原路径消失", !Files.exists(ids.resolve(caller, "notes/proj/a.txt")), "");
        String moved = String.valueOf(((Result.Ok) step2).data());
        check("移入回收站 notes/.trash/<时间戳>/",
                moved.contains("notes/.trash/") && moved.contains("moved_to"), moved);

        // token 一次性：直接验 ConfirmGate（notes 层的复用会先撞"文件已删"，
        // 那测的是存在性不是令牌语义，所以一次性在机制层验）
        snowleopard.core.confirm.ConfirmGate once = new snowleopard.core.confirm.ConfirmGate();
        snowleopard.core.confirm.ConfirmGate.Challenge och =
                once.issue("notes.delete", "p", "d1");
        check("首次核销成功", once.confirm("notes.delete", och.token(), "d1") instanceof Result.Ok, "");
        Result reuse = once.confirm("notes.delete", och.token(), "d1");
        check("token 不可复用（单次使用）", reuse instanceof Result.Error
                && ((Result.Error) reuse).desc().contains("无效"), reuse.level());

        // 搜索
        notes.write("proj/c.txt", "关键词测试：雪豹");
        Result s = notes.search("雪豹");
        check("搜索命中正文", String.valueOf(((Result.Ok) s).data()).contains("c.txt"),
                String.valueOf(((Result.Ok) s).data()));
        // 回收站不进搜索
        Result s2 = notes.search("第一版");
        check("回收站内容不进搜索（稀疏/洁净）",
                !String.valueOf(((Result.Ok) s2).data()).contains("a.txt"),
                String.valueOf(((Result.Ok) s2).data()));

        // 动作隔离：给 A 动作签发的 token 不能用于 B 动作（M5d 的 memory_correct 会复用同一机制）
        snowleopard.core.confirm.ConfirmGate cg = new snowleopard.core.confirm.ConfirmGate();
        snowleopard.core.confirm.ConfirmGate.Challenge ch =
                cg.issue("notes.delete", "预览", "digest-x");
        Result cross = cg.confirm("memory.correct", ch.token(), "digest-x");
        check("token 对其他动作无效（动作隔离）", cross instanceof Result.Error
                && ((Result.Error) cross).desc().contains("不能用于"), cross.level());
        Result right = cg.confirm("notes.delete", ch.token(), "digest-x");
        check("同动作同摘要核销成功", right instanceof Result.Ok, right.level());
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

    private WorkCheck() {
    }
}
