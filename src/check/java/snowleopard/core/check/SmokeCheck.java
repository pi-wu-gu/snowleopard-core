package snowleopard.core.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * M0 冒烟检查：构建设施本身是否可靠（2026-10-22 22:20 (UTC+8)）。
 *
 * <p>断言的是"工程骨架的意图"而不是"现状碰巧如此"：
 * ① 产物类数 = 源文件推算数（无漏编——星期五 {@code RuleApprover} 整类缺失的教训）；
 * ② 产物零 MC 引用（雪豹硬约束：core 不与我的世界耦合）；
 * ③ 检查器不进产物（src/check 不属于产品树）。
 */
public final class SmokeCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path src = root.resolve("src/main/java");
        Path out = root.resolve("build/classes");
        Path jar = root.resolve("dist/snowleopard-core.jar");

        check("源码树存在", Files.isDirectory(src), src.toString());
        check("产物目录存在", Files.isDirectory(out), out.toString());
        check("jar 已产出", Files.isRegularFile(jar), jar.toString());

        // ① 无漏编：每个 .java 的顶层类必须有对应 .class
        List<Path> sources;
        try (Stream<Path> s = Files.walk(src)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        int missing = 0;
        for (Path j : sources) {
            String rel = src.relativize(j).toString().replace('\\', '/');
            String cls = rel.substring(0, rel.length() - ".java".length());
            // package-info.java 是包级 javadoc（无注解时 javac 按设计不产 class）——不是漏编。
            if (rel.endsWith("package-info.java")) {
                continue;
            }
            if (!Files.isRegularFile(out.resolve(cls + ".class"))) {
                missing++;
                System.out.println("  ✗ 缺 class: " + cls);
            }
        }
        check("无漏编（源文件 " + sources.size() + " 个各有 class）", missing == 0, "缺 " + missing);

        // ② 零 MC 引用：扫产物常量池里的 net/minecraft、net/neoforged
        int mcRefs = 0;
        try (Stream<Path> s = Files.walk(out)) {
            for (Path c : s.filter(p -> p.toString().endsWith(".class")).toList()) {
                byte[] b = Files.readAllBytes(c);
                if (contains(b, "net/minecraft") || contains(b, "net/neoforged")) {
                    mcRefs++;
                    System.out.println("  ✗ 含 MC 引用: " + out.relativize(c));
                }
            }
        }
        check("产物零 MC 引用（jdeps 同级断言）", mcRefs == 0, "命中 " + mcRefs);

        // ③ 检查器不进产物
        List<Path> checks;
        try (Stream<Path> s = Files.walk(out)) {
            checks = s.filter(p -> {
                String f = p.getFileName().toString();
                return f.endsWith("Check.class") || p.toString().contains("check" + java.io.File.separator);
            }).toList();
        }
        check("检查器不进产物", checks.isEmpty(), checks.toString());

        System.out.println((fail == 0 ? "PASS" : "FAIL") + " smoke: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= hay.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
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

    private SmokeCheck() {
    }
}
