package snowleopard.core.confirm;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 两段式<b>模型自确认</b>（M4，2026-10-22 22:55 (UTC+8)，分析 §8.3 / 21:38 更正）。
 *
 * <p><b>是"确认"不是"审批"</b>：没有外部审批方，相关组件只服务于模型自身。
 * 删除/纠错这类不可轻做的动作分两段：
 * <ol>
 *   <li>首段（无令牌）→ 返回<b>待办清单 + {@code confirm_token}</b>，不执行；</li>
 *   <li>模型看过清单后带令牌二次调用 → 才执行。</li>
 * </ol>
 *
 * <p>令牌<b>绑定目标内容摘要</b>（{@code digest}）：两段之间目标被改过 → 令牌失效，
 * 防"看的是 A、删的是 B"。令牌单次使用、带 TTL（默认 10 分钟）。
 */
public final class ConfirmGate {

    /** 令牌有效期（毫秒）。超时的令牌视为不存在。 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 首段产出的确认单（含令牌）。 */
    public record Challenge(String token, String action, String preview,
                            String digest, long issuedTs) {
    }

    private final Map<String, Challenge> pending = new LinkedHashMap<>();

    /**
     * 首段：签发确认单。不执行任何动作。
     *
     * @param action  动作名（如 {@code notes.delete}）——令牌只对该动作有效
     * @param preview 给模型看的"将要发生什么"
     * @param digest  目标内容摘要（内容变了令牌即失效）
     */
    public synchronized Challenge issue(String action, String preview, String digest) {
        purgeExpired();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : bytes) {
            sb.append(String.format("%02x", x));
        }
        Challenge c = new Challenge(sb.toString(), action, preview,
                digest == null ? "" : digest, System.currentTimeMillis());
        pending.put(c.token(), c);
        return c;
    }

    /**
     * 二段：核销令牌。成功返回 {@code Ok(Challenge)}（调用方据此执行）。
     *
     * <p>令牌的消耗语义（2026-10-22 22:58 复核后定）：
     * <ul>
     *   <li><b>动作不符 → 不烧令牌</b>——那是派发错误，不该毁掉模型已做好的确认意图；</li>
     *   <li><b>内容摘要不符 → 烧掉</b>——内容变了，必须重看重签；</li>
     *   <li>核销成功 → 烧掉（单次使用）。</li>
     * </ul>
     */
    public synchronized Result confirm(String action, String token, String digest) {
        purgeExpired();
        if (token == null || token.isBlank()) {
            return Result.error("缺少 confirm_token——两段式确认：先调一次拿令牌，看过清单后带令牌二次调用");
        }
        Challenge c = pending.get(token);   // 先看不取：动作不符时不烧
        if (c == null) {
            return Result.error("confirm_token 无效或已过期（单次使用，有效期 " + (TTL_MS / 60000)
                    + " 分钟）——请重新调用以获取新令牌");
        }
        if (!c.action().equals(action)) {
            return Result.error("confirm_token 是给「" + c.action() + "」签发的，不能用于「" + action + "」");
        }
        pending.remove(token);   // 从这里起单次使用：取出即作废
        String d = digest == null ? "" : digest;
        if (!c.digest().equals(d)) {
            return Result.error("目标内容在确认期间已变化，confirm_token 失效——请重新调用查看最新内容");
        }
        return Result.ok(c);
    }

    /** 显式作废全部待确认单。 */
    public synchronized void clear() {
        pending.clear();
    }

    /** 当前待确认单数（诊断用）。 */
    public synchronized int pendingCount() {
        purgeExpired();
        return pending.size();
    }

    private void purgeExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        Iterator<Map.Entry<String, Challenge>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().issuedTs() < cutoff) {
                it.remove();
            }
        }
    }

    /** 内容摘要：键 + 内容的轻量指纹（令牌绑定用，非密码学用途）。 */
    public static String digest(String key, String content) {
        String src = key + " " + (content == null ? "" : content);
        int h = src.hashCode();
        long h2 = 0;
        for (int i = 0; i < src.length(); i++) {
            h2 = h2 * 131 + src.charAt(i);
        }
        return Integer.toHexString(h) + "-" + Long.toHexString(h2);
    }
}
