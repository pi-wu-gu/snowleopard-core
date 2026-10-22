package snowleopard.core.memory;

/**
 * 压缩器契约（M5b，2026-10-22 23:12 (UTC+8)，分析 §三.二.3）。
 *
 * <p>压缩<b>由 LLM 负责</b>（草案原文），core 不含模型——宿主注入实现。
 * 每次压缩是一次模型调用，<b>进消耗统计</b>（与会话管理组联动，由宿主记账）。
 *
 * <p>失败语义（21:22 裁决：冷却补做）：实现<b>失败就抛异常</b>，调用方（{@code Rollup}）
 * 丢弃当次产物、记 WARN、进入冷却，下一轮末或启动 catchup 时重试——
 * 丢的是"那次失败的压缩产物"，原始记录永远还在。
 */
public interface Compressor {

    /**
     * 压缩一段源文本为摘要文本。
     *
     * @param purpose  用途标识（如 {@code round→day}、{@code days→week}）——实现可据此决定提示词
     * @param source   源文本（当天所有轮压缩段 / 下层全部档）
     * @return 摘要文本（非空）
     * @throws RuntimeException 压缩失败（调用方冷却重试）
     */
    String compress(String purpose, String source);
}
