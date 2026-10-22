package snowleopard.core.report;

/**
 * 装载期问题记录（M1，2026-10-22 22:27 (UTC+8)）。
 *
 * <p>两级语义（分析 §九.2）：{@code WARN} = 匹配失败（配置跨档共用是常态，继续跑）；
 * {@code ERROR} = 参数错误（拒绝该项生效、退回默认，不许"半生效"）。
 * 非 OK 的结果必须带描述——规范在构造期强制，不靠人记得写。
 */
public record Issue(Level level, String where, String message) {

    public enum Level {
        WARN, ERROR
    }

    public static Issue warn(String where, String message) {
        return new Issue(Level.WARN, where, message);
    }

    public static Issue error(String where, String message) {
        return new Issue(Level.ERROR, where, message);
    }

    @Override
    public String toString() {
        return level + " " + where + ": " + message;
    }
}
