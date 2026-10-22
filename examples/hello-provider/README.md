# hello-provider（最小接入示例）

> 2026-10-23 01:35 (UTC+8) · 配套文档：`docs/接入指南-2026-10-23.md`

两个文件：`HelloProvider`（SPI 工具组）+ `HelloMain`（装配到调用的最小闭环）。
编译只需核心 jar（示例与外部接入者同处境——不依赖任何测试代码）：

```bash
javac -encoding UTF-8 -cp ../../dist/snowleopard-core.jar -d out src/*.java
java  -cp ../../dist/snowleopard-core.jar:out HelloMain /tmp/hello-home   # Windows 用 ;
```

`./build.sh check examples` 会自动编译并运行本示例、断言输出标记——示例即规格。
