package snowleopard.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析（零依赖产物，2026-10-22 22:27 (UTC+8)）。
 *
 * <p>只覆盖配置与清单需要的形态：对象 / 数组 / 字符串 / 数字（整数进 Long）/ 布尔 / null。
 * 不支持注释、尾逗号、单引号——配置文件写错就报错，不做宽容猜测（宽容解析是"配置悄悄失效"的温床）。
 * 对象重复键取后者（与 JS 行为一致）；需要"别名冲突"语义由上层（{@code Names}）判定。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** 解析任意 JSON 值。 */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.err("JSON 末尾有多余内容");
        }
        return v;
    }

    /** 解析 JSON 对象（配置文件形态）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层必须是对象: " + abbreviate(v));
        }
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (i >= s.length()) {
            throw err("意外的输入结尾");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw err("对象键必须是字符串");
            }
            String k = string();
            ws();
            if (peek() != ':') {
                throw err("键之后缺少 ':'");
            }
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                return m;
            }
            throw err("对象未闭合");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                return a;
            }
            throw err("数组未闭合");
        }
    }

    private String string() {
        i++; // "
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw err("字符串未闭合");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                if (i >= s.length()) {
                    throw err("转义序列不完整");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw err("\\u 序列不完整");
                        }
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("未知转义: \\" + e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || ".eE+-".indexOf(s.charAt(i)) >= 0)) {
            i++;
        }
        String t = s.substring(start, i);
        try {
            if (t.matches("-?\\d+")) {
                return Long.parseLong(t);
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw err("非法数字: " + t);
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw err("期望 " + word);
        }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err("意外的输入结尾");
        }
        return s.charAt(i);
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON 解析错误（位置 " + i + "）: " + msg);
    }

    private static String abbreviate(Object v) {
        String t = String.valueOf(v);
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
