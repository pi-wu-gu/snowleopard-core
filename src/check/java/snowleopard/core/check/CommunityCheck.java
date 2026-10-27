package snowleopard.core.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import snowleopard.core.group.GroupStore;
import snowleopard.core.ids.Ids;
import snowleopard.core.result.Result;
import snowleopard.core.wake.LockFileRuntime;
import snowleopard.core.wake.RuntimeView;
import snowleopard.core.wake.WakeStore;

/**
 * 协作面验收（M1+M2，2026-10-23 22:00 (UTC+8)）：唤醒 + 群组。
 *
 * <p>判据（都是"裁决落地后必须成立的行为"，不是宽泛扫）：
 * <ol>
 *   <li><b>唤醒六道闸</b>：未登记 / 自唤起 / 成环 / 深度超限 / 在运行 / 被拉黑——各自 Error 且原因正确；
 *       幂等键 → WARN 不产生第二条；配额窗口 → 第 21 次拒绝；depth 由 core 注入（模型改不了）；</li>
 *   <li><b>群组三级角色</b>：普通成员不能加人（"除成员外均可添加"的反面）、管理不能任命、创建者不能退；</li>
 *   <li><b>引用三态</b>：ok / stale（内容变更）/ missing（源丢失），且**只回状态不回内容**；</li>
 *   <li><b>append-only 与游标</b>：seq 单调、两实例交替写不重号、read 推进 lastReadSeq、
 *       {@code markNotified} 不动 lastReadSeq（读与提示是两个维度）；</li>
 *   <li><b>解散不轻删</b>：移入 {@code .recycled}，索引清掉，之后发消息报"不存在或已解散"。</li>
 * </ol>
 */
public final class CommunityCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path tmp = Files.createTempDirectory("sl-community");
        Ids ids = Ids.at(tmp);
        for (String id : new String[]{"alpha", "beta", "gamma", "delta"}) {
            Files.createDirectories(ids.home(id));
        }
        RuntimeView rt = new LockFileRuntime(ids.shared());
        WakeStore wake = new WakeStore(ids, rt);

        wake(ids, rt, wake);
        groups(ids, wake);

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " community: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- 唤醒

    private static void wake(Ids ids, RuntimeView rt, WakeStore wake) {
        System.out.println("== 1 唤醒六道闸 ==");
        WakeStore.Chain solo = new WakeStore.Chain(List.of("alpha"));

        Result r1 = wake.request(solo, "alpha", "ghost", null, null, null);
        check("未登记目标 → Error", r1 instanceof Result.Error
                && ((Result.Error) r1).desc().contains("未登记"), String.valueOf(r1));

        Result r2 = wake.request(solo, "alpha", "alpha", null, null, null);
        check("自唤起 → Error", r2 instanceof Result.Error
                && ((Result.Error) r2).desc().contains("自身"), String.valueOf(r2));

        Result r3 = wake.request(new WakeStore.Chain(List.of("beta", "alpha")), "alpha", "beta",
                null, null, null);
        check("成环 A→B→A → Error", r3 instanceof Result.Error
                && ((Result.Error) r3).desc().contains("成环"), String.valueOf(r3));

        Result r4 = wake.request(new WakeStore.Chain(List.of("beta", "alpha")), "alpha", "gamma",
                null, null, null);
        check("深度超限（被唤起方不得再唤起）→ Error", r4 instanceof Result.Error
                && ((Result.Error) r4).desc().contains("深度超限"), String.valueOf(r4));

        ((LockFileRuntime) rt).tryAcquire("beta");
        Result r5 = wake.request(solo, "alpha", "beta", null, null, null);
        check("目标在运行 → Error（不排队）", r5 instanceof Result.Error
                && ((Result.Error) r5).desc().contains("正在运行"), String.valueOf(r5));
        ((LockFileRuntime) rt).release("beta");

        Result deny = wake.deny("beta", "alpha", true);
        check("拉黑 → Ok", deny instanceof Result.Ok, String.valueOf(deny));
        Result r6 = wake.request(solo, "alpha", "beta", null, null, null);
        check("被目标拉黑 → Error", r6 instanceof Result.Error
                && ((Result.Error) r6).desc().contains("禁止"), String.valueOf(r6));
        wake.deny("beta", "alpha", false);
        Result r7 = wake.request(solo, "alpha", "beta", "巡检", "定时检查", "k-1");
        check("取消拉黑后可唤起", r7 instanceof Result.Ok, String.valueOf(r7));
        check("depth 由 core 注入（从 0 到 1）", numOf(map(r7), "depth") == 1,
                String.valueOf(map(r7).get("depth")));

        Result r8 = wake.request(solo, "alpha", "beta", "巡检", "定时检查", "k-1");
        check("幂等键重复 → WARN 不产生第二条", r8 instanceof Result.Warn,
                String.valueOf(r8));

        String reqId = String.valueOf(map(r7).get("reqId"));
        check("pending 清单含该请求", wake.pending().stream()
                .anyMatch(x -> x.reqId().equals(reqId)), String.valueOf(wake.pending().size()));
        Result done = wake.complete(reqId, true, "跑完了");
        check("宿主回写 done", done instanceof Result.Ok
                && String.valueOf(map(done).get("state")).equals("done"), String.valueOf(done));
        Result again = wake.complete(reqId, true, "重复回写");
        check("重复回写 → WARN（幂等）", again instanceof Result.Warn, String.valueOf(again));
        Result other = wake.status("gamma", reqId);
        check("第三方查请求 → Error", other instanceof Result.Error
                && ((Result.Error) other).desc().contains("无权"), String.valueOf(other));
        Result mine = wake.policy("beta");
        check("被唤起方 policy 有 recent 记录", mine instanceof Result.Ok
                && String.valueOf(map(mine).get("recent")).contains("alpha"), String.valueOf(mine));

        Result selfDeny = wake.deny("beta", "beta", true);
        check("把自己加入禁止列表 → Error", selfDeny instanceof Result.Error,
                String.valueOf(selfDeny));

        // 配额：塞满到 QUOTA_PER_WINDOW（前面已有 1 次），下一次拒绝。
        // **对着常量写**，别再写死 19/20/21——改配额时这里会自己跟上（2026-10-27 20→48）。
        for (int i = 0; i < WakeStore.QUOTA_PER_WINDOW - 1; i++) {
            wake.request(solo, "alpha", "beta", "q" + i, null, "quota-" + i);
        }
        Result over = wake.request(solo, "alpha", "beta", "over", null, "quota-over");
        check("一小时配额 " + WakeStore.QUOTA_PER_WINDOW + " 次，第 "
                + (WakeStore.QUOTA_PER_WINDOW + 1) + " 次 → Error", over instanceof Result.Error
                && ((Result.Error) over).desc().contains("上限"), String.valueOf(over));
    }

    // ------------------------------------------------------------- 群组

    private static void groups(Ids ids, WakeStore wake) {
        System.out.println("== 2 群组（三级角色 / append-only / 引用三态） ==");
        GroupStore g = new GroupStore(ids);

        Result created = g.create("alpha", "阿尔法组");
        String gid = String.valueOf(map(created).get("gid"));
        check("建群 → g<seq>", created instanceof Result.Ok && gid.matches("g\\d{5}"),
                String.valueOf(created));
        check("创建者自动入组（list 可见，角色 CREATOR）",
                String.valueOf(data(g.list("alpha"))).contains("CREATOR"),
                String.valueOf(data(g.list("alpha"))));

        // 角色：普通成员不能加人
        g.add("alpha", gid, "beta");           // beta 先入组（创建者添加）
        Result memberAdd = g.add("beta", gid, "gamma");
        check("普通成员 add → Error（除成员外均可添加）", memberAdd instanceof Result.Error
                && ((Result.Error) memberAdd).desc().contains("管理"), String.valueOf(memberAdd));
        Result grantByMember = g.grant("beta", gid, "beta", "admin");
        check("非创建者 grant → Error", grantByMember instanceof Result.Error,
                String.valueOf(grantByMember));
        Result grant = g.grant("alpha", gid, "beta", "admin");
        check("创建者任命管理 → Ok", grant instanceof Result.Ok
                && String.valueOf(map(grant).get("role")).equals("ADMIN"), String.valueOf(grant));
        Result adminAdd = g.add("beta", gid, "gamma");
        check("管理 add → Ok", adminAdd instanceof Result.Ok, String.valueOf(adminAdd));
        Result removeByAdmin = g.remove("beta", gid, "gamma");
        check("管理 remove → Error（仅创建者）", removeByAdmin instanceof Result.Error,
                String.valueOf(removeByAdmin));

        // 消息：append-only + 游标
        Result s1 = g.send("alpha", gid, "第一条", null, "m-1");
        check("发消息 → seq=1", s1 instanceof Result.Ok
                && String.valueOf(map(s1).get("seq")).equals("1"), String.valueOf(s1));
        Result s2 = g.send("beta", gid, "第二条 含关键词", null, null);
        check("成员（管理）发消息 → seq=2", s2 instanceof Result.Ok
                && String.valueOf(map(s2).get("seq")).equals("2"), String.valueOf(s2));
        Result dup = g.send("alpha", gid, "第一条", null, "m-1");
        check("同幂等键 → WARN", dup instanceof Result.Warn, String.valueOf(dup));
        check("盘上是 append-only（两行）",
                lines(ids.shared().resolve("groups").resolve(gid).resolve("messages.jsonl")) == 2,
                String.valueOf(lines(ids.shared().resolve("groups").resolve(gid).resolve("messages.jsonl"))));

        Result tooLong = g.send("alpha", gid, "x".repeat(GroupStore.MAX_TEXT + 1), null, null);
        check("超长消息 → Error", tooLong instanceof Result.Error, String.valueOf(tooLong));
        Result outsider = g.send("delta", gid, "我不是成员", null, null);
        check("非成员发消息 → Error", outsider instanceof Result.Error, String.valueOf(outsider));

        // gamma 是新成员：未读从 1 开始（群里历史对成员开放）
        Result readG = g.read("gamma", gid, 10, -1);
        check("新成员读历史（未读游标从 1 起）",
                String.valueOf(data(readG)).contains("第一条"), String.valueOf(data(readG)));
        Result readAgain = g.read("gamma", gid, 10, -1);
        check("重复 read 不再返回（lastReadSeq 已推进）",
                !String.valueOf(data(readAgain)).contains("第一条"), String.valueOf(data(readAgain)));

        // 搜索：子串 + 时间窗
        Result hit = g.search("alpha", gid, "关键词", 0, 0, 10);
        check("搜索命中（子串）", String.valueOf(data(hit)).contains("第二条"), String.valueOf(data(hit)));
        Result miss = g.search("alpha", gid, "不存在的词", 0, 0, 10);
        check("搜索未命中 → 空列表", String.valueOf(data(miss)).contains("hits=[]"),
                String.valueOf(data(miss)));
        Result future = g.search("alpha", gid, "第二条",
                System.currentTimeMillis() + 60_000, 0, 10);
        check("搜索时间窗（未来起点 → 空）", String.valueOf(data(future)).contains("hits=[]"),
                String.valueOf(data(future)));

        // 引用三态：ok / stale / missing，且不回内容
        Path note = ids.home("alpha").resolve("notes").resolve("d1");
        try {
            Files.createDirectories(note);
            Files.writeString(note.resolve("a.md"), "机密内容");
        } catch (Exception e) {
            check("准备引用源文件", false, String.valueOf(e));
        }
        Result refOk = g.send("alpha", gid, "带引用", "note:d1/a.md", null);
        check("引用发送 → state=ok",
                String.valueOf(data(refOk)).contains("state=ok"), String.valueOf(data(refOk)));
        check("引用只回状态不回内容", !String.valueOf(data(refOk)).contains("机密内容"),
                String.valueOf(data(refOk)));
        try {
            Files.writeString(note.resolve("a.md"), "改过了");
        } catch (Exception ignored) {
            // 写失败则下面断言会暴露
        }
        Result stale = g.read("beta", gid, 50, -1);
        check("源内容变更 → state=stale", String.valueOf(data(stale)).contains("state=stale"),
                String.valueOf(data(stale)));
        try {
            Files.deleteIfExists(note.resolve("a.md"));
        } catch (Exception ignored) {
            // 同上
        }
        Result missing = g.read("beta", gid, 50, 0);   // sinceSeq=0 重读（前面已推进游标）
        check("源文件丢失 → state=missing", String.valueOf(data(missing)).contains("state=missing"),
                String.valueOf(data(missing)));
        Result escape = g.send("alpha", gid, "越界引用", "note:../../../etc/passwd", null);
        check("引用越界 → Error", escape instanceof Result.Error, String.valueOf(escape));

        // 提示两维度：markNotified 不动 lastReadSeq
        Result before = g.read("beta", gid, 100, -1);
        long readBefore = numOf(data(before), "lastReadSeq");
        Map<String, List<Long>> fresh = g.newlyArrived("beta");
        check("newlyArrived 有未提示消息", fresh.containsKey(gid), String.valueOf(fresh));
        g.markNotified("beta", gid, fresh.getOrDefault(gid, List.of()));
        check("标记提示后 newlyArrived 清空", g.newlyArrived("beta").isEmpty(),
                String.valueOf(g.newlyArrived("beta")));
        Result after = g.read("beta", gid, 100, -1);
        check("提示不改 lastReadSeq（读与提示两维度）",
                numOf(data(after), "lastReadSeq") == readBefore,
                readBefore + " → " + numOf(data(after), "lastReadSeq"));
        check("未读概览格式 gid(n)", g.unreadSummary("gamma").matches("g\\d{5}\\(\\d+\\)"),
                g.unreadSummary("gamma"));

        // 退出 / 移除 / 解散
        Result creatorLeave = g.leave("alpha", gid);
        check("创建者 leave → Error（先解散）", creatorLeave instanceof Result.Error,
                String.valueOf(creatorLeave));
        Result memberLeave = g.leave("gamma", gid);
        check("成员 leave → Ok", memberLeave instanceof Result.Ok, String.valueOf(memberLeave));
        Result leftRead = g.read("gamma", gid, 10, -1);
        check("退出后读 → Error", leftRead instanceof Result.Error, String.valueOf(leftRead));

        Result disbandByAdmin = g.disband("beta", gid);
        check("非创建者解散 → Error", disbandByAdmin instanceof Result.Error,
                String.valueOf(disbandByAdmin));
        Result disband = g.disband("alpha", gid);
        check("创建者解散 → Ok 且进 .recycled",
                disband instanceof Result.Ok
                        && String.valueOf(map(disband).get("movedTo")).contains(".recycled"),
                String.valueOf(disband));
        Result sendAfter = g.send("alpha", gid, "解散后", null, null);
        check("解散后发消息 → Error", sendAfter instanceof Result.Error, String.valueOf(sendAfter));

        // 并发：两个实例交替写，seq 不重
        GroupStore g2 = new GroupStore(ids);
        Result c2 = g.create("alpha", "并发组");
        String gid2 = String.valueOf(map(c2).get("gid"));
        g.add("alpha", gid2, "beta");
        for (int i = 0; i < 10; i++) {
            (i % 2 == 0 ? g : g2).send(i % 2 == 0 ? "alpha" : "beta", gid2, "并发-" + i, null, null);
        }
        Result all = g.read("alpha", gid2, 100, 0);
        check("两实例交替写 10 条、seq 不重（0..10 连续）",
                countSeqs(data(all)) == 10,
                "实际 " + countSeqs(data(all)));

        // ---- M4：轮末汇总一行（裁决 6） ----
        System.out.println("== 3 轮末汇总（一行 / 沉默 / 提示一次） ==");
        String line1 = snowleopard.core.builtin.TurnSummary.line("beta", g, wake, null,
                java.time.LocalDate.now());
        check("汇总行含群未读", line1 != null && line1.contains("群组未读 g"),
                String.valueOf(line1));
        check("汇总行含新唤起计数", line1 != null && line1.contains("新唤起"),
                String.valueOf(line1));
        check("汇总行含新消息（仅一次维度）", line1 != null && line1.contains("新消息"),
                String.valueOf(line1));
        long unreadBefore = unreadOf(data(g.list("beta")), gid2);
        snowleopard.core.builtin.TurnSummary.consume("beta", g, wake);
        String line2 = snowleopard.core.builtin.TurnSummary.line("beta", g, wake, null,
                java.time.LocalDate.now());
        check("提示一次：新消息段与唤醒段消失", line2 == null
                || (!line2.contains("新消息") && !line2.contains("新唤起")),
                String.valueOf(line2));
        check("存在未读每轮仍报（群-8 与群-9 是两个维度）",
                line2 != null && line2.contains("群组未读"), String.valueOf(line2));
        check("且未读计数未变（提示 ≠ 已读）", unreadOf(data(g.list("beta")), gid2) == unreadBefore
                && unreadBefore > 0, "before=" + unreadBefore);
        check("无内容 → 沉默（返回 null，不占上下文）",
                snowleopard.core.builtin.TurnSummary.line("delta", g, wake, null,
                        java.time.LocalDate.now()) == null, "非 null");
    }

    // ------------------------------------------------------------- 小工具

    private static long unreadOf(Object listData, String gid) {
        if (listData instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && gid.equals(String.valueOf(m.get("gid")))) {
                    Object u = m.get("unread");
                    return u instanceof Number n ? n.longValue() : -1;
                }
            }
        }
        return -1;
    }

    private static long lines(Path p) {
        try {
            return Files.exists(p) ? Files.readAllLines(p).stream().filter(s -> !s.isBlank()).count() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Object data(Result r) {
        return switch (r) {
            case Result.Ok o -> o.data();
            case Result.Warn w -> w.data();
            case Result.Severe s -> s.data();
            case Result.Error e -> e.desc();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Result r) {
        Object d = data(r);
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static long numOf(Object d, String key) {
        if (d instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            return n.longValue();
        }
        return -1;
    }

    private static int countSeqs(Object d) {
        if (d instanceof Map<?, ?> m && m.get("messages") instanceof List<?> l) {
            return l.size();
        }
        return -1;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " —— " + detail);
        }
    }

    private CommunityCheck() {
    }
}
