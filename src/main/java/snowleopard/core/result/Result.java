package snowleopard.core.result;

/**
 * 四级结果（M3，2026-10-22 22:46 (UTC+8)，分析 §二.六）。
 *
 * <p>{@code OK}=照常返回数据；{@code WARN}=数据 + 描述（有影响不严重）；
 * {@code SEVERE}=**部分数据** + 描述（有影响很严重但仍能运作——"带伤运行"，不是 ERROR 的别名）；
 * {@code ERROR}=无数据、只有失败原因。
 *
 * <p><b>非 OK 必须带描述</b>——构造期强制（无描述直接抛），把规范变成 API 形状，
 * 而不是靠人记得写。星期五的反面教材：工具失败曾返回 {@code "[failed]"} 文本被包成成功
 * （假成功家族），所以四级在<b>返回值类型</b>上分家，不靠字符串约定。
 *
 * <p>SEVERE 的使用边界（§8.4"不需要不用强加"）：内置组以 OK/WARN/ERROR 为主，
 * 语义确为"带伤运行"才用 SEVERE（会话预算收束、memory_repair 残留缺口），不为凑数制造。
 */
public sealed interface Result permits Result.Ok, Result.Warn, Result.Severe, Result.Error {

    record Ok(Object data) implements Result {
    }

    record Warn(Object data, String desc) implements Result {
        public Warn {
            if (desc == null || desc.isBlank()) {
                throw new IllegalArgumentException("WARN 必须带描述（§二.六.2）");
            }
        }
    }

    /** 带伤运行：{@code data} 是**部分**数据（还能给多少给多少）。 */
    record Severe(Object data, String desc) implements Result {
        public Severe {
            if (desc == null || desc.isBlank()) {
                throw new IllegalArgumentException("SEVERE 必须带描述（§二.六.2）");
            }
        }
    }

    record Error(String desc) implements Result {
        public Error {
            if (desc == null || desc.isBlank()) {
                throw new IllegalArgumentException("ERROR 必须带描述（§二.六.2）");
            }
        }
    }

    static Result ok(Object data) {
        return new Ok(data);
    }

    static Result warn(Object data, String desc) {
        return new Warn(data, desc);
    }

    static Result severe(Object data, String desc) {
        return new Severe(data, desc);
    }

    static Result error(String desc) {
        return new Error(desc);
    }

    /** 级别名（审计/日志用）。 */
    default String level() {
        return switch (this) {
            case Ok o -> "OK";
            case Warn w -> "WARN";
            case Severe s -> "SEVERE";
            case Error e -> "ERROR";
        };
    }

    /** 成功判定：只有 OK 是成功（WARN 是"成功但要说话"）。 */
    default boolean okLevel() {
        return this instanceof Ok;
    }
}
