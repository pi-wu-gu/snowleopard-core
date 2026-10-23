/**
 * 内置工具组（2026-10-23 19:20 (UTC+8)）：把 core 的四个自带能力
 * （会话 / 记忆 / 任务清单 / 笔记）做成<b>可注册、可调用</b>的工具组。
 *
 * <p>为什么住在 core（分析 §4.4）：四组是"决策核心"的一部分，与 MC 无关；
 * 它们经 {@code ToolGroupProvider} 走<b>同一条</b>注册路径（来源 {@code builtin:core:*}），
 * 不享受特权通道——否则"内置"会退化成"绕过注册表的影子能力"。
 *
 * <p>三件套分工：
 * <ul>
 *   <li>{@link snowleopard.core.builtin.BuiltinGroups} —— 清单（组简介 + 工具 schema），进注册表注入面；</li>
 *   <li>{@link snowleopard.core.builtin.BuiltinToolkit} —— 执行面（组名 + 工具名 + 参数 → {@code Result}）；</li>
 *   <li>{@link snowleopard.core.builtin.BuiltinHome} —— 执行面的能力来源（调用 ID 的家，红线 3 隔离）。</li>
 * </ul>
 *
 * <p>两级注入照旧：组简介进上下文（{@code briefs()}），工具详解按需查（{@code help}）。
 */
package snowleopard.core.builtin;
