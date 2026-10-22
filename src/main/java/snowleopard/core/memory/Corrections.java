package snowleopard.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import snowleopard.core.confirm.ConfirmGate;
import snowleopard.core.ids.Ids;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

/**
 * 记忆纠错条目（M5d，2026-10-22 23:12 (UTC+8)，分析 §三.二.8）。
 *
 * <p>模型可添加/移除纠错条目（<b>两段式模型自确认</b>——是确认不是审批，21:38 更正）。
 * 条目带<b>两个时间戳</b>：{@code created_ts}（首段签发）与 {@code confirmed_ts}（二段核销）。
 *
 * <p><b>读取对应记忆时自动加载</b>：{@link #listFor} 按 {@code target_ref} 匹配
 * （如 {@code day:2026-10-22} / {@code week:2026-W43} / {@code round:<key>#<seq>}），
 * 读取方把命中条目以 {@code ⚠ 已纠正} 行注入（星期五的同构）。
 *
 * <p>移除走同一确认机制（真删但留审计——审计由宿主的审计日志记账，本类只管条目）。
 * 孤儿条目（指向的记忆不存在）<b>挂起 + WARN</b>，不当场丢（分析 §8.2）。
 */
public final class Corrections {

    /** 一条纠错。 */
    public record Entry(String id, String targetRef, String note,
                        long createdTs, long confirmedTs) {
    }

    private final Ids ids;
    private final Ids.Caller caller;
    private final ConfirmGate gate = new ConfirmGate();
    private final AtomicLong seq = new AtomicLong();

    public Corrections(Ids ids, Ids.Caller caller) {
        this.ids = ids;
        this.caller = caller;
        load();
    }

    /**
     * 添加纠错（两段式）：无 token → 首段返回预览 + {@code confirm_token}，不落盘；
     * 带 token → 核销后落盘（记双时间戳）。
     */
    public Result add(String targetRef, String note, String token) {
        if (targetRef == null || targetRef.isBlank()) {
            return Result.error("纠错必须指定 target_ref（如 day:2026-10-22）");
        }
        if (note == null || note.isBlank()) {
            return Result.error("纠错内容不能为空");
        }
        String digest = ConfirmGate.digest(targetRef, note);
        if (token == null || token.isBlank()) {
            ConfirmGate.Challenge ch = gate.issue("memory.correct.add", preview(targetRef, note), digest);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("action", "memory.correct.add");
            d.put("target_ref", targetRef);
            d.put("note", note);
            d.put("confirm_token", ch.token());
            d.put("note_hint", "两段式确认：未写入。确认无误后带 confirm_token 再次调用才生效");
            return Result.ok(d);
        }
        Result confirmed = gate.confirm("memory.correct.add", token, digest);
        if (confirmed instanceof Result.Error) {
            return confirmed;
        }
        long now = System.currentTimeMillis();
        Entry e = new Entry("c" + seq.incrementAndGet(), targetRef, note, now, now);
        entries.add(e);
        save();
        return Result.ok(e);
    }

    /**
     * 移除纠错（两段式，真删但审计由宿主记账）。
     */
    public Result remove(String id, String token) {
        Entry e = find(id);
        if (e == null) {
            return Result.error("纠错条目不存在: " + id);
        }
        String digest = ConfirmGate.digest(e.targetRef(), e.note());
        if (token == null || token.isBlank()) {
            ConfirmGate.Challenge ch = gate.issue("memory.correct.remove",
                    "移除纠错 " + id + "（" + e.targetRef() + "）: " + e.note(), digest);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("action", "memory.correct.remove");
            d.put("entry", e);
            d.put("confirm_token", ch.token());
            d.put("note_hint", "两段式确认：未移除。带 confirm_token 再次调用才生效");
            return Result.ok(d);
        }
        Result confirmed = gate.confirm("memory.correct.remove", token, digest);
        if (confirmed instanceof Result.Error) {
            return confirmed;
        }
        entries.removeIf(x -> x.id().equals(id));
        save();
        return Result.ok(Map.of("removed", id));
    }

    /** 读取自动加载：按 target_ref 匹配的条目（含精确与前缀匹配）。 */
    public List<Entry> listFor(String targetRef) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.targetRef().equals(targetRef) || targetRef.startsWith(e.targetRef())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 全部条目（诊断/孤儿检查）。 */
    public List<Entry> list() {
        return new ArrayList<>(entries);
    }

    /**
     * 孤儿检查：target_ref 指向的记忆不存在 → 挂起报告（WARN 语义，不丢条目，分析 §8.2）。
     *
     * @param exists 判定 target_ref 是否有对应记忆的谓词
     */
    public Result orphans(java.util.function.Predicate<String> exists) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!exists.test(e.targetRef())) {
                out.add(e.id() + " → " + e.targetRef());
            }
        }
        if (out.isEmpty()) {
            return Result.ok(Map.of("orphans", 0));
        }
        return Result.warn(Map.of("orphans", out),
                "有 " + out.size() + " 条纠错指向不存在的记忆（挂起不丢）——多为该期无调用的稀疏空档");
    }

    /** 注入行（读取对应记忆时追加）。 */
    public static String inject(Entry e) {
        return "⚠ 已纠正（" + e.confirmedTs() + "）: " + e.note();
    }

    // ------------------------------------------------------------- 持久化

    private final List<Entry> entries = new ArrayList<>();

    private Entry find(String id) {
        for (Entry e : entries) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private Path file() {
        return ids.mkdirs(caller, "memory").resolve("corrections.json");
    }

    private String preview(String targetRef, String note) {
        return "将对 " + targetRef + " 添加纠错: " + note;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            Object s = root.get("seq");
            if (s instanceof Number n) {
                seq.set(n.longValue());
            }
            Object raw = root.get("entries");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        entries.add(new Entry(
                                String.valueOf(m.get("id")),
                                String.valueOf(m.get("targetRef")),
                                String.valueOf(m.getOrDefault("note", "")),
                                asLong(m.get("createdTs")),
                                asLong(m.get("confirmedTs"))));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seq", seq.get());
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("targetRef", e.targetRef());
            m.put("note", e.note());
            m.put("createdTs", e.createdTs());
            m.put("confirmedTs", e.confirmedTs());
            list.add(m);
        }
        root.put("entries", list);
        try {
            Files.writeString(file(), Json.dump(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("纠错条目写盘失败: " + e, e);
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
