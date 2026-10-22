package snowleopard.core.check;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 波次 2（2026-10-23 01:35 (UTC+8)）：示例即规格——examples/ 必须真编译、真跑通。
 *
 * <p>判定意图（不是"现状碰巧如此"）：
 * ① 示例<b>只靠核心 jar</b> 编译（与外部接入者同处境——不许引用 src/check 或 src/main 裸类）；
 * ② HelloMain 跑通最小闭环并在输出里留下**行为标记**（装载全名 / 启动报告 / help / Result）；
 * ③ 示例与文档教程同源（教程说"跑通它"，跑不通即文档漂移）。
 *
 * <p>编译经 {@link JavaCompiler} 在进程内做，运行经隔离 {@code URLClassLoader}
 * （父加载器=bootstrap）——示例可见 JDK 与 jar，不可见检查树，天然防"借用了测试类"。
 */
public final class ExamplesCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path jar = root.resolve("dist/snowleopard-core.jar");
        Path exampleSrc = root.resolve("examples/hello-provider/src");
        Path out = root.resolve("build/examples-classes");
        Path home = root.resolve("build/examples-home");

        check("示例源码树存在", Files.isDirectory(exampleSrc), exampleSrc.toString());
        check("核心 jar 已产出", Files.isRegularFile(jar), jar.toString());

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        check("系统 JavaCompiler 可用（需要 JDK，JRE 跑不了本检查）", javac != null, "");

        List<Path> sources;
        try (Stream<Path> s = Files.walk(exampleSrc)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        check("示例源文件非空", !sources.isEmpty(), exampleSrc.toString());

        // ① 只靠 jar 编译：-cp 里只有核心 jar
        deleteRecursively(out);
        Files.createDirectories(out);
        boolean compiled = false;
        if (javac != null && !sources.isEmpty()) {
            List<String> argv = new ArrayList<>(List.of(
                    "-encoding", "UTF-8",
                    "-cp", jar.toString(),
                    "-d", out.toString()));
            for (Path p : sources) {
                argv.add(p.toString());
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc = javac.run(null, null, err, argv.toArray(new String[0]));
            compiled = rc == 0;
            if (!compiled) {
                System.out.println("  编译错误输出: " + err.toString(StandardCharsets.UTF_8));
            }
        }
        check("示例只靠核心 jar 编译通过", compiled, "");

        // ② 隔离运行：父加载器=bootstrap（JDK 类可见；检查树不可见）
        if (compiled) {
            deleteRecursively(home);
            Files.createDirectories(home);
            String captured;
            try (URLClassLoader cl = new URLClassLoader(new URL[]{
                    jar.toUri().toURL(), out.toUri().toURL()}, null)) {
                Class<?> mainCls = cl.loadClass("HelloMain");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                PrintStream old = System.out;
                try {
                    System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
                    mainCls.getMethod("main", String[].class)
                            .invoke(null, (Object) new String[]{home.toString()});
                } finally {
                    System.setOut(old);
                }
                captured = buf.toString(StandardCharsets.UTF_8);
            }
            System.out.println("  ---- HelloMain 输出 ----");
            for (String line : captured.split("\n")) {
                System.out.println("  | " + line.trim());
            }
            check("输出含装载全名（装载器拼限定名）",
                    captured.contains("装载: local:hello-demo:echo"), "实际: " + firstLine(captured, "装载"));
            check("输出含 help 组头（查询注入面）",
                    captured.contains("组 local:hello-demo:echo"), "实际: " + firstLine(captured, "help"));
            check("输出含启动报告",
                    captured.contains("启动报告"), "实际: " + firstLine(captured, "启动报告"));
            check("todo add → Ok（Result 四级之首）",
                    captured.contains("todo add → Ok"), "实际: " + firstLine(captured, "todo"));
        }

        System.out.println("PASS examples: 通过 " + pass + " · 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String firstLine(String captured, String prefix) {
        for (String line : captured.split("\n")) {
            if (line.trim().startsWith(prefix)) {
                return line.trim();
            }
        }
        return "(无)";
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    private ExamplesCheck() {
    }
}
