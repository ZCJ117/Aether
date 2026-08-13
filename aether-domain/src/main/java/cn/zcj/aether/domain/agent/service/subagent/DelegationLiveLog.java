package cn.zcj.aether.domain.agent.service.subagent;

import java.util.List;

/**
 * 每子Agent直播日志端口 — 对齐 hermes delegation_live_log.py LiveTranscriptWriter。
 * <p>实现位于 infrastructure（FileDelegationLiveLog）；SubagentLifecycleService 经
 * ObjectProvider 可选注入，无 Bean 时所有调用 no-op。</p>
 * <p>纪律（实现必须遵守）：写失败绝不上抛进 agent 循环；首次失败即禁用该 writer，
 * 降级 debug log；close 幂等。</p>
 */
public interface DelegationLiveLog {

    /** 打开一个子Agent直播日志（写 header：id / goal / started）。 */
    void open(String delegationId, String goal);

    /** 追加一行（实现加时间戳前缀 + 单行折叠/截断）。 */
    void append(String delegationId, String role, String line);

    /** 冲刷缓冲（append-mode 实现可为 no-op 语义占位）。 */
    void flush(String delegationId);

    /** 写终态摘要并关闭（幂等）。 */
    void close(String delegationId, String summary);

    /** 读取末 n 行。 */
    List<String> tail(String delegationId, int n);
}
