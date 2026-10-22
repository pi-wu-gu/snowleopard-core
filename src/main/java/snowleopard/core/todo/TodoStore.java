package snowleopard.core.todo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import snowleopard.core.ids.Ids;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

/**
 * 任务清单组（M4，2026-10-22 22:55 (UTC+8)，分析 §三.三）。
 *
 * <p>条目 + <b>一层</b>子条目（星期五 todo 的同构：parentId 限一层，子条目不得再有子）。
 * 状态机 {@code PENDING → DONE / BLOCKED / FAILED}。
 *
 * <p><b>删除语义</b>（§8.3）：{@link #delete} = <b>标记不删</b>（{@code DELETED}），
 * 父删时子随之标记；{@link #purge} 才物理删（级联子条目）。与记忆的"不轻删"一致。
 *
 * <p>落盘 {@code ids/<ID>/todo/todo.json}（挂载随调用 ID——§九.3：工具工作目录挂
 * 调用方 ID 的家，ID 之间互相隔离）。每条目带创建/更新时间戳（用户全局偏好）。
 */
public final class TodoStore {

    /** 条目状态。{@code DELETED} = 标记删除（仍在盘上，purge 才消失）。 */
    public enum Status {
        PENDING, DONE, BLOCKED, FAILED, DELETED
    }

    /** 一条任务。时间戳为 epoch 毫秒。 */
    public record Item(String id, String text, Status status, String parentId,
                       long createdTs, long updatedTs) {
    }

    private final Ids ids;
    private final Ids.Caller caller;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public TodoStore(Ids ids, Ids.Caller caller) {
        this.ids = ids;
        this.caller = caller;
        load();
    }

    /** 添加条目。 */
    public synchronized Result add(String text) {
        return addInternal(null, text);
    }

    /**
     * 添加子条目（限一层）：父本身是子条目 → 拒绝。
     */
    public synchronized Result addSub(String parentId, String text) {
        Item parent = items.get(parentId);
        if (parent == null || parent.status() == Status.DELETED) {
            return Result.error("父条目不存在或已标记删除: " + parentId);
        }
        if (parent.parentId() != null) {
            return Result.error("只支持一层子条目（" + parentId + " 自己就是子条目）——再深的层级不建");
        }
        return addInternal(parentId, text);
    }

    /**
     * 标记状态（{@code DONE / BLOCKED / FAILED / PENDING}）。
     * 对 {@code DELETED} 条目：{@code PENDING} = 恢复（与 {@link #delete} 的说明一致），
     * 其他状态先恢复再标。不接受 {@code DELETED}——那走 {@link #delete}（标记删除是独立语义）。
     */
    public synchronized Result mark(String id, Status status) {
        Item it = items.get(id);
        if (it == null) {
            return Result.error("条目不存在: " + id);
        }
        if (status == Status.DELETED) {
            return Result.error("状态 DELETED 不经 mark 设置——删除走 delete（标记）或 purge（物理）");
        }
        if (it.status() == Status.DELETED && status != Status.PENDING) {
            return Result.error("条目 " + id + " 已标记删除——恢复用 mark(id, PENDING)，之后才能标其他状态");
        }
        Item moved = new Item(it.id(), it.text(), status, it.parentId(), it.createdTs(), now());
        items.put(id, moved);
        save();
        return Result.ok(moved);
    }

    /**
     * 标记删除（不物理删）：本条与（若为父）其全部子条目一起置 {@code DELETED}。
     * 对话里看到的清单不再出现它们，但盘上仍在，purge 前可 {@code mark(PENDING)} 恢复。
     */
    public synchronized Result delete(String id) {
        Item it = items.get(id);
        if (it == null) {
            return Result.error("条目不存在: " + id);
        }
        List<String> touched = new ArrayList<>();
        long ts = now();
        items.put(id, new Item(it.id(), it.text(), Status.DELETED, it.parentId(), it.createdTs(), ts));
        touched.add(id);
        if (it.parentId() == null) {
            for (Item sub : childrenOf(id)) {
                if (sub.status() != Status.DELETED) {
                    items.put(sub.id(), new Item(sub.id(), sub.text(), Status.DELETED,
                            sub.parentId(), sub.createdTs(), ts));
                    touched.add(sub.id());
                }
            }
        }
        save();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("marked_deleted", touched);
        d.put("note", "标记删除（未物理删除）——物理删除用 purge；恢复用 mark(id, PENDING)");
        return Result.ok(d);
    }

    /** 物理删除（级联子条目）。不可恢复——与 {@link #delete} 的"标记"语义分开。 */
    public synchronized Result purge(String id) {
        Item it = items.get(id);
        if (it == null) {
            return Result.error("条目不存在: " + id);
        }
        List<String> removed = new ArrayList<>();
        for (Item sub : childrenOf(id)) {
            items.remove(sub.id());
            removed.add(sub.id());
        }
        items.remove(id);
        removed.add(id);
        save();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("purged", removed);
        return Result.ok(d);
    }

    /** 列出未标记删除的条目（含子条目，带 parentId）。 */
    public synchronized List<Item> list() {
        List<Item> out = new ArrayList<>();
        for (Item it : items.values()) {
            if (it.status() != Status.DELETED) {
                out.add(it);
            }
        }
        return out;
    }

    /** 盘上总条目数（含标记删除的——诊断用）。 */
    public synchronized int size() {
        return items.size();
    }

    private Result addInternal(String parentId, String text) {
        if (text == null || text.isBlank()) {
            return Result.error("条目文本不能为空");
        }
        long ts = now();
        String id = "t" + seq.incrementAndGet();
        Item it = new Item(id, text.trim(), Status.PENDING, parentId, ts, ts);
        items.put(id, it);
        save();
        return Result.ok(it);
    }

    private List<Item> childrenOf(String parentId) {
        List<Item> out = new ArrayList<>();
        for (Item it : items.values()) {
            if (parentId.equals(it.parentId())) {
                out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- 持久化

    private Path file() {
        return ids.mkdirs(caller, "todo").resolve("todo.json");
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            Object rawSeq = root.get("seq");
            if (rawSeq instanceof Number n) {
                seq.set(n.longValue());
            }
            Object rawItems = root.get("items");
            if (rawItems instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> m = (Map<String, Object>) o;
                    items.put(String.valueOf(m.get("id")), new Item(
                            String.valueOf(m.get("id")),
                            String.valueOf(m.getOrDefault("text", "")),
                            Status.valueOf(String.valueOf(m.getOrDefault("status", "PENDING"))),
                            m.get("parentId") == null ? null : String.valueOf(m.get("parentId")),
                            asLong(m.get("createdTs")),
                            asLong(m.get("updatedTs"))));
                }
            }
        } catch (RuntimeException | IOException e) {
            // 坏文件不锁死启动：清空重来并在内存中继续（下次 save 覆盖）。
            // TODOStore 的数据是工作记忆不是唯一账本——但要把事实说出来
            items.clear();
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seq", seq.get());
        List<Object> list = new ArrayList<>();
        for (Item it : items.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.id());
            m.put("text", it.text());
            m.put("status", it.status().name());
            m.put("parentId", it.parentId());
            m.put("createdTs", it.createdTs());
            m.put("updatedTs", it.updatedTs());
            list.add(m);
        }
        root.put("items", list);
        try {
            Files.writeString(file(), Json.dump(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("任务清单写盘失败: " + e, e);
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 时间戳展示（用户偏好：记录带时间戳）。 */
    public static String formatTs(long epochMs) {
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
