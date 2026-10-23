package snowleopard.core.builtin;

import snowleopard.core.memory.Corrections;
import snowleopard.core.memory.MemoryStore;
import snowleopard.core.memory.RawLog;
import snowleopard.core.memory.Rollup;
import snowleopard.core.notes.NoteStore;
import snowleopard.core.session.SessionGate;
import snowleopard.core.todo.TodoStore;

/**
 * 内置工具的执行面能力来源（2026-10-23 19:20 (UTC+8)）。
 *
 * <p><b>为什么是接口而不是直接收 {@code SnowLeopard.CallerScope}</b>：
 * 门面（{@code snowleopard.core}）要调 {@link BuiltinGroups} 完成注册，
 * 若本包再反向依赖门面就是包级双向依赖（星期五的结构性风险：同一职责两处实现必漂移）。
 * 接口放本包、由 {@code CallerScope} 实现，依赖方向单向：门面 → 内置包。
 *
 * <p><b>红线 3（ID 文件夹=模型的家）</b>：本接口的每个 getter 都必须来自**同一个调用 ID**
 * 的作用域——实现方不得混入别的 ID 的 Store。跨 ID 读写在能力层就不存在。
 */
public interface BuiltinHome {

    TodoStore todos();

    NoteStore notes();

    MemoryStore memory();

    RawLog rawLog();

    Rollup rollup();

    Corrections corrections();

    SessionGate session();
}
