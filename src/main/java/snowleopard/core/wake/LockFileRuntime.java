package snowleopard.core.wake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机默认运行态：<b>双保险</b>（2026-10-23 21:40 裁决）——进程内表 + 跨进程锁文件。
 *
 * <p>为什么两样都要：
 * <ul>
 *   <li>只用进程内表 → 两个进程（双实例、服务端 + 客户端）抢同一 ID 时互相看不见；</li>
 *   <li>只用锁文件 → 同进程内多次 acquire 的语义还要靠文件系统，多一层 IO 且删锁失败即永久卡死；</li>
 * </ul>
 * 两者同时成立才算"拿到"：进程内 {@code add} 成功 <b>且</b> 锁文件 {@code CREATE_NEW} 成功。
 *
 * <p><b>残留锁</b>：进程崩溃会留下锁文件。按 mtime 判陈旧（默认 6 小时），陈旧即视为不在运行并尝试删除——
 * 宁可让极端情况下的"假空闲"发生（可观测：日志有 WARN），也不让一次崩溃永久锁死某个 ID。
 */
public final class LockFileRuntime implements RuntimeView {

    /** 默认陈旧阈值：6 小时（一次会话不可能这么长）。 */
    public static final long DEFAULT_STALE_MS = 6 * 60 * 60 * 1000L;

    private final Path dir;
    private final long staleMs;
    private final Set<String> inProcess = ConcurrentHashMap.newKeySet();

    public LockFileRuntime(Path sharedRoot) {
        this(sharedRoot, DEFAULT_STALE_MS);
    }

    public LockFileRuntime(Path sharedRoot, long staleMs) {
        if (sharedRoot == null) {
            throw new IllegalArgumentException("sharedRoot 必填（不猜存储根）");
        }
        this.dir = sharedRoot.resolve("run");
        this.staleMs = staleMs;
    }

    /**
     * 抢占用（宿主在真正启动一次会话时调用）。
     *
     * @return true=抢到；false=已在运行（进程内或别的进程持有有效锁）
     */
    public boolean tryAcquire(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            return false;
        }
        if (!inProcess.add(callerId)) {
            return false;   // 本进程已在跑
        }
        Path lock = lockOf(callerId);
        try {
            Files.createDirectories(dir);
            Files.writeString(lock, ProcessHandle.current().pid() + " " + System.currentTimeMillis(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (isStale(lock)) {
                try {
                    Files.deleteIfExists(lock);
                    Files.writeString(lock, ProcessHandle.current().pid() + " "
                            + System.currentTimeMillis(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    return true;   // 陈旧锁已被回收
                } catch (IOException ignored) {
                    inProcess.remove(callerId);
                    return false;
                }
            }
            inProcess.remove(callerId);   // 别的进程在跑：不占进程内名额
            return false;
        } catch (IOException e) {
            inProcess.remove(callerId);
            return false;
        }
    }

    /** 释放（宿主会话结束时调用；幂等）。 */
    public void release(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            return;
        }
        inProcess.remove(callerId);
        try {
            Files.deleteIfExists(lockOf(callerId));
        } catch (IOException ignored) {
            // 删不掉也只是残留锁，按 mtime 会被回收
        }
    }

    @Override
    public boolean isRunning(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            return false;
        }
        if (inProcess.contains(callerId)) {
            return true;
        }
        Path lock = lockOf(callerId);
        if (!Files.exists(lock)) {
            return false;
        }
        if (isStale(lock)) {
            try {
                Files.deleteIfExists(lock);
            } catch (IOException ignored) {
                // 删不掉就当它在跑（保守）
                return true;
            }
            return false;
        }
        return true;
    }

    @Override
    public String source() {
        return "锁文件 + 进程内表（单机默认）";
    }

    private Path lockOf(String callerId) {
        // ID 已过 Ids.Caller 校验（字母数字开头，可含 _-），不含路径分隔符
        return dir.resolve(callerId + ".lock");
    }

    private boolean isStale(Path lock) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(lock).toMillis() > staleMs;
        } catch (IOException e) {
            return false;
        }
    }
}
