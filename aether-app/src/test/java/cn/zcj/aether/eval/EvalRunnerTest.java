package cn.zcj.aether.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0(4.1) 效果评估体系 —— 50 case 评测集 + 自动化跑批 + eval-report.json。
 *
 * <ul>
 *   <li>四类用例：tool_selection(20) / multi_step(15) / context_retention(10) / permission(5)</li>
 *   <li>双模式：确定性（默认，mock 决策核，CI 可跑）；
 *       真实（AETHER_EVAL_MODE=real + DEEPSEEK_API_KEY，模型自主决策）</li>
 *   <li>产物：{@code aether-app/target/eval-report.json}（每 case pass/fail + 耗时 + token，分类汇总）</li>
 *   <li>A/B 回归演示：{@code scripts/eval-ab-demo.sh}（变异用例集 → 捕获通过率下降）</li>
 * </ul>
 *
 * <p>用例集路径可用环境变量 AETHER_EVAL_CASES 覆盖（默认 classpath:eval/cases.jsonl）。</p>
 */
class EvalRunnerTest {

    private static final List<EvalEngines.CaseOutcome> OUTCOMES =
            Collections.synchronizedList(new ArrayList<>());
    private static final boolean REAL_MODE = RealLlmSupport.enabled();

    static Stream<EvalCase> cases() throws IOException {
        String override = System.getenv("AETHER_EVAL_CASES");
        String content = override != null && !override.isBlank()
                ? Files.readString(Path.of(override), StandardCharsets.UTF_8)
                : new String(readClasspath("eval/cases.jsonl"), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        return content.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> {
                    try {
                        return mapper.readValue(l, EvalCase.class);
                    } catch (IOException e) {
                        throw new IllegalStateException("case 解析失败: " + l, e);
                    }
                });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void runCase(EvalCase evalCase) {
        EvalEngines.CaseOutcome outcome = switch (evalCase.category) {
            case "tool_selection" -> EvalEngines.runToolSelection(evalCase, REAL_MODE);
            case "multi_step" -> EvalEngines.runMultiStep(evalCase, REAL_MODE);
            case "context_retention" -> EvalEngines.runRetention(evalCase);
            case "permission" -> EvalEngines.runPermission(evalCase);
            default -> EvalEngines.fail(evalCase, System.currentTimeMillis(), "未知 category: " + evalCase.category);
        };
        OUTCOMES.add(outcome);
        assertTrue(outcome.pass(),
                () -> "eval case 失败 [" + outcome.id() + "] " + outcome.category() + ": " + outcome.detail());
    }

    @AfterAll
    static void writeReport() throws IOException {
        int total = OUTCOMES.size();
        int passed = (int) OUTCOMES.stream().filter(EvalEngines.CaseOutcome::pass).count();
        Map<String, Map<String, Object>> byCategory = new TreeMap<>();
        for (EvalEngines.CaseOutcome o : OUTCOMES) {
            byCategory.computeIfAbsent(o.category(), k -> {
                Map<String, Object> m = new TreeMap<>();
                m.put("total", 0);
                m.put("passed", 0);
                return m;
            });
            Map<String, Object> m = byCategory.get(o.category());
            m.put("total", (Integer) m.get("total") + 1);
            if (o.pass()) {
                m.put("passed", (Integer) m.get("passed") + 1);
            }
        }
        byCategory.values().forEach(m -> m.put("passRate",
                total == 0 ? 0 : String.format("%.1f%%", 100.0 * (Integer) m.get("passed") / (Integer) m.get("total"))));

        Map<String, Object> report = new TreeMap<>();
        report.put("mode", REAL_MODE ? "real" : "deterministic");
        report.put("total", total);
        report.put("passed", passed);
        report.put("passRate", total == 0 ? "n/a" : String.format("%.1f%%", 100.0 * passed / total));
        report.put("categories", byCategory);
        report.put("cases", OUTCOMES);

        Path out = Path.of("target", "eval-report.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

        System.out.println("\n================ Eval 报告 ================");
        System.out.println("mode=" + (REAL_MODE ? "real" : "deterministic")
                + "  passed=" + passed + "/" + total
                + "  passRate=" + report.get("passRate"));
        byCategory.forEach((k, v) -> System.out.println("  " + k + ": " + v.get("passed") + "/" + v.get("total")
                + " (" + v.get("passRate") + ")"));
        System.out.println("报告已写入: " + out.toAbsolutePath());
        System.out.println("==========================================");
    }

    private static byte[] readClasspath(String path) throws IOException {
        try (var in = EvalRunnerTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + path);
            }
            return in.readAllBytes();
        }
    }
}
