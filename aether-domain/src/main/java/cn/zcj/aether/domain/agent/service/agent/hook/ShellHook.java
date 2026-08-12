package cn.zcj.aether.domain.agent.service.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令式生命周期钩子 — 对齐 hermes shell_hooks.py _spawn（L433）。
 * <p>invoke 时 spawn 外部进程：无 shell（防注入）、stdin 传 HookContext JSON、
 * 超时强杀、输出入日志。异常一律吞掉不抛穿（对齐 _spawn 的 error 字段语义）。</p>
 * <p>限制：stdout/stderr 在 {@code waitFor} 之后才排空，子进程大输出（超过 OS 管道缓冲）
 * 可能阻塞至超时强杀——面向通知类短生命命令，接受为已知限制。</p>
 */
@Slf4j
public class ShellHook implements LifecycleHook {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LOG_LEN = 500;

    private final ShellHookSpec spec;

    public ShellHook(ShellHookSpec spec) {
        this.spec = spec;
    }

    @Override
    public Set<HookPoint> points() {
        return Set.of(spec.point());
    }

    @Override
    public void onHook(HookPoint point, HookContext ctx) {
        List<String> argv = shlexSplit(spec.command());
        if (argv.isEmpty()) {
            log.warn("ShellHook 命令为空，跳过: point={}", point);
            return;
        }
        String stdinJson = toJson(ctx);
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            // stdin 传 JSON 载荷（对齐 hermes _spawn input=stdin_json）
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
            }
            boolean finished = process.waitFor(spec.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly(); // 超时强杀（对齐 hermes TimeoutExpired 处理）
                log.warn("ShellHook 超时强杀: cmd={} timeoutMs={}", spec.command(), spec.timeoutMs());
                return;
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.warn("ShellHook 非零退出: cmd={} exit={} stderr={}",
                        spec.command(), process.exitValue(), truncate(stderr));
            } else {
                log.info("ShellHook 执行完成: cmd={} stdout={}",
                        spec.command(), truncate(stdout));
            }
        } catch (Exception e) {
            log.warn("ShellHook 执行异常（已吞掉）: cmd={} err={}", spec.command(), e.getMessage());
        }
    }

    /**
     * 命令拆分为 argv（无 shell，防注入；对齐 hermes shlex.split + shell=False）。
     * 说明：按空白简单拆分，不支持引号内空格（与 hermes shlex 的差异，接受作为限制）。
     */
    private static List<String> shlexSplit(String command) {
        return Arrays.stream(command.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String toJson(HookContext ctx) {
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("HookContext 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_LOG_LEN) return s;
        return s.substring(0, MAX_LOG_LEN) + "...(" + s.length() + " chars)";
    }
}
