package snowleopard.core.wake;

/**
 * 运行态权威（M0 契约，2026-10-23 21:40 (UTC+8)）。
 *
 * <p><b>"目标是否正在运行"必须由本接口回答，core 不许自己推断</b>——
 * {@code SessionGate} 只有轮次与用量，拿它推"空闲"会在"上一轮结束但进程仍在跑"时误判成空闲，
 * 结果就是<b>同一 ID 双开</b>（两个实例抢同一 gameDir 那类事故的同构）。
 *
 * <p>实现方：宿主 / 壳（真正启动会话的那一层）。单机默认实现见 {@link LockFileRuntime}。
 *
 * <p>返回 {@code true} = 在运行 → {@link WakeStore} 直接判失败（草案："已经在运行则失败"），
 * <b>不排队</b>：排队会把"失败"悄悄变成"稍后执行"，是隐式并发。
 */
public interface RuntimeView {

    /** 该 ID 是否正在运行（一次会话进行中）。 */
    boolean isRunning(String callerId);

    /** 运行态来源描述——进错误信息，便于排障时知道是谁判的。 */
    default String source() {
        return "宿主 RuntimeView";
    }
}
