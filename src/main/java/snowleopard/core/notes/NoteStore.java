package snowleopard.core.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import snowleopard.core.confirm.ConfirmGate;
import snowleopard.core.ids.Ids;
import snowleopard.core.result.Result;

/**
 * 笔记组（M4，2026-10-22 22:55 (UTC+8)，分析 §三.四）。
 *
 * <p>模型可自由管理的外置笔记区：建夹 / 建文 / 读 / 追加 / 删除 / 搜索，
 * 全部限定在 {@code ids/<ID>/notes/} 下（挂载随调用 ID，ID 间隔离）。
 *
 * <p><b>删除 = 两段式自确认 + 回收站</b>（§8.3）：首段返回待删清单 + {@code confirm_token}，
 * 模型带令牌二次调用才执行；执行是<b>移入 {@code notes/.trash/<时间戳>/}</b> 而不是抹掉
 * （时间戳即文件夹名）。令牌绑定内容摘要——两段之间改过内容，旧令牌失效。
 *
 * <p>笔记<b>不进记忆的被动检索</b>（分析 §三.四 的设计决定）：笔记是模型主动管理的
 * 外置记忆，被动注入会和压缩记忆打架；查询走本组的 {@link #search}。
 */
public final class NoteStore {

    private static final String ROOT = "notes";
    private static final String TRASH = "notes/.trash";

    private final Ids ids;
    private final Ids.Caller caller;
    private final ConfirmGate confirm = new ConfirmGate();

    public NoteStore(Ids ids, Ids.Caller caller) {
        this.ids = ids;
        this.caller = caller;
    }

    /** 建文件夹（可多级，按需建）。 */
    public Result mkdir(String rel) {
        Path p = safe(rel);
        if (p == null) {
            return Result.error("非法笔记路径: " + rel);
        }
        try {
            Files.createDirectories(p);
            return Result.ok(Map.of("created", display(rel), "ts", ts()));
        } catch (IOException e) {
            return Result.error("创建文件夹失败: " + e.getMessage());
        }
    }

    /** 建文（覆盖式写入）。追加请用 {@link #append}——追加是安全操作，覆盖是破坏性的。 */
    public Result write(String rel, String content) {
        Path p = safe(rel);
        if (p == null) {
            return Result.error("非法笔记路径: " + rel);
        }
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("written", display(rel));
            d.put("bytes", content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
            d.put("ts", ts());
            return Result.ok(d);
        } catch (IOException e) {
            return Result.error("写入失败: " + e.getMessage());
        }
    }

    /** 追加（不存在则创建）。 */
    public Result append(String rel, String content) {
        Path p = safe(rel);
        if (p == null) {
            return Result.error("非法笔记路径: " + rel);
        }
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("appended", display(rel));
            d.put("ts", ts());
            return Result.ok(d);
        } catch (IOException e) {
            return Result.error("追加失败: " + e.getMessage());
        }
    }

    /** 读取（文本）。 */
    public Result read(String rel) {
        Path p = safe(rel);
        if (p == null) {
            return Result.error("非法笔记路径: " + rel);
        }
        try {
            if (!Files.exists(p)) {
                return Result.error("笔记不存在: " + display(rel));
            }
            if (Files.isDirectory(p)) {
                List<String> names = new ArrayList<>();
                try (Stream<Path> s = Files.list(p)) {
                    s.forEach(x -> names.add(x.getFileName().toString()));
                }
                return Result.ok(Map.of("dir", display(rel), "entries", names));
            }
            return Result.ok(Map.of("path", display(rel),
                    "content", Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Result.error("读取失败: " + e.getMessage());
        }
    }

    /**
     * 删除（两段式，§8.3）：
     * <ul>
     *   <li>{@code token == null} → 首段：返回待删清单 + {@code confirm_token}，<b>不执行</b>；</li>
     *   <li>带 token 二次调用 → 核销令牌并移入回收站 {@code notes/.trash/<时间戳>/}。</li>
     * </ul>
     */
    public Result delete(String rel, String token) {
        Path p = safe(rel);
        if (p == null) {
            return Result.error("非法笔记路径: " + rel);
        }
        try {
            if (!Files.exists(p)) {
                return Result.error("笔记不存在: " + display(rel));
            }
            String digest = digestOf(p);
            String preview = previewOf(p);
            if (token == null || token.isBlank()) {
                ConfirmGate.Challenge ch = confirm.issue("notes.delete", preview, digest);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("action", "notes.delete");
                d.put("target", display(rel));
                d.put("preview", preview);
                d.put("confirm_token", ch.token());
                d.put("note", "两段式确认：这是第一步，未执行删除。看过上面的待删内容后，"
                        + "带 confirm_token 再次调用本工具才真正删除（移入回收站 notes/.trash/）");
                return Result.ok(d);
            }
            Result confirmed = confirm.confirm("notes.delete", token, digest);
            if (confirmed instanceof Result.Error) {
                return confirmed;
            }
            // 核销成功 → 移入回收站（时间戳即文件夹名，§三.4）
            String stamp = ts().replace(':', '-').replace(' ', '_');
            Path trashDir = ids.mkdirs(caller, TRASH + "/" + stamp);
            Path target = trashDir.resolve(p.getFileName().toString());
            Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("deleted", display(rel));
            d.put("moved_to", TRASH + "/" + stamp + "/" + p.getFileName());
            d.put("ts", ts());
            return Result.ok(d);
        } catch (IOException e) {
            return Result.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 关键词搜索（笔记不进记忆的被动检索，只能主动查——§三.4 设计决定）。
     * 命中文件名或正文，返回路径 + 命中行预览。
     */
    public Result search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Result.error("搜索关键词不能为空");
        }
        Path root = ids.mkdirs(caller, ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    if (rel.startsWith(".trash")) {
                        return FileVisitResult.CONTINUE;   // 回收站不进搜索
                    }
                    String name = file.getFileName().toString();
                    if (name.contains(keyword)) {
                        hits.add(Map.of("path", ROOT + "/" + rel, "match", "filename"));
                    }
                    try {
                        String text = Files.readString(file, StandardCharsets.UTF_8);
                        int idx = text.indexOf(keyword);
                        if (idx >= 0) {
                            int lineStart = text.lastIndexOf('\n', idx) + 1;
                            int lineEnd = text.indexOf('\n', idx);
                            String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
                            hits.add(Map.of("path", ROOT + "/" + rel, "match", "content",
                                    "line", line.length() > 120 ? line.substring(0, 120) + "…" : line));
                        }
                    } catch (IOException ignored) {
                        // 二进制/不可读文件跳过——搜索不该因单个文件失败
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return Result.error("搜索失败: " + e.getMessage());
        }
        return Result.ok(Map.of("keyword", keyword, "hits", hits, "count", hits.size()));
    }

    /** 当前待确认单数（诊断）。 */
    public int pendingConfirms() {
        return confirm.pendingCount();
    }

    // ------------------------------------------------------------- 内部

    private Path safe(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        String norm = rel.replace('\\', '/');
        if (norm.startsWith("/") || norm.contains(":") || norm.contains("..")) {
            return null;
        }
        try {
            return ids.resolve(caller, ROOT + "/" + norm);
        } catch (Ids.PathEscape e) {
            return null;
        }
    }

    private String display(String rel) {
        return ROOT + "/" + rel.replace('\\', '/');
    }

    private String digestOf(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            StringBuilder sb = new StringBuilder("dir:");
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(Files::isRegularFile).sorted().forEach(x -> {
                    try {
                        sb.append(p.relativize(x)).append('=')
                                .append(Files.size(x)).append(';');
                    } catch (IOException e) {
                        sb.append('?');
                    }
                });
            }
            return ConfirmGate.digest(p.toString(), sb.toString());
        }
        return ConfirmGate.digest(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
    }

    private String previewOf(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            List<String> names = new ArrayList<>();
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(Files::isRegularFile).forEach(x -> names.add(p.relativize(x).toString()));
            }
            return "目录 " + p.getFileName() + "（含 " + names.size() + " 个文件: "
                    + (names.size() > 8 ? names.subList(0, 8) + " …" : names) + "）";
        }
        String text = Files.readString(p, StandardCharsets.UTF_8);
        String head = text.length() > 200 ? text.substring(0, 200) + "…" : text;
        return "文件 " + p.getFileName() + "（" + text.getBytes(StandardCharsets.UTF_8).length
                + " 字节）: " + head;
    }

    private static String ts() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
