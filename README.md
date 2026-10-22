# 雪豹决策核心（Snow Leopard Decision Core）

> **说明：本项目几乎完全由 AI 完成**——设计分析、架构决策、全部代码与文档均由 AI 在与用户的  
> 对话中产出，用户负责提出需求、裁决设计分歧与验收。工程纪律沿袭自同作者的  
> 星期五决策核心项目（MC AI 模组）的同法协作经验。  
> 文档与记录一律带时间戳（`YYYY-MM-DD HH:MM (UTC+8)`）。

**无界面的 AI 决策核心依赖**：被外部模组调用的决策内核——工具组注册与检索、ID 级存储隔离、  
分级记忆、调用编排。纯 Java 21、零第三方依赖、**零 MC 耦合**（经 `mod-shell` 作为模组加载）。

## 快速开始

- **接入**（外部模组/工具组作者）：`docs/概念地图-2026-10-23.md`（10 分钟）→ `docs/接入指南-2026-10-23.md`（30 分钟）→ 跑 `examples/hello-provider/`
- **改核心**（贡献者）：`docs/INDEX.md` 阅读路径 → `docs/行为规格清单-2026-10-23.md` → 对应 Check 代码
- **只跑起来**：下三条命令；全部 headless 检查秒级全绿

```bash
./build.sh            # 核心 jar + 源码 jar（全量编译，jdeps 硬检查零 MC 引用）
./build.sh check all  # 全部 headless 检查（秒级）
./build.sh publish    # maven 布局（snowleopard.core:snowleopard-core）
```

需要 JDK 21（`--release 21` 为硬要求）。产物：`dist/snowleopard-core.jar`。

## 定位

| 项    | 内容                                           |
| ---- | -------------------------------------------- |
| 是什么  | 负责**决策**的核心依赖：工具调用的门禁/编排/记忆                  |
| 不是什么 | 不做界面、不做输入采集、不含子模型（可组合出）、不耦合 MC               |
| 输出通道 | 仅三个：工具返回值（四级结果）/ 文件系统（ID 家）/ 审计日志            |
| 二次确认 | 发给**模型自身**的确认（两段式调用 + `confirm_token`），无审批概念 |
| 标准入口 | 装配门面 `SnowLeopard.bootstrap`（两级句柄；手动装配=展开式，进阶用） |

## 文档

- `docs/INDEX.md` —— 文档地图（上手/参考/史料三栏 + 按角色的阅读路径）
- 里程碑状态（M0–M7 全部完成）在 `docs/开发流程-2026-10-22.md`；后续接 NeoForge mod-shell（core 保持零 MC）
