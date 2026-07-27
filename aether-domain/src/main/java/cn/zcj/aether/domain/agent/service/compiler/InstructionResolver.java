package cn.zcj.aether.domain.agent.service.compiler;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * instruction 解析器：支持内联文本、classpath: 文件引用、file: 文件引用。
 *
 * <pre>
 *   instruction: "classpath:agent/prompts/travel-planner.md"   → 从 classpath 加载
 *   instruction: "file:/home/user/prompts/coder.md"            → 从文件系统加载
 *   instruction: |                                              → 内联文本（向后兼容）
 *     你是 "旅程设计师"...
 * </pre>
 */
@Slf4j
public final class InstructionResolver {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";

    private InstructionResolver() {
    }

    /**
     * 解析 instruction 值：如果是文件引用（classpath: 或 file: 前缀），加载文件内容；
     * 否则原样返回（内联文本）。
     *
     * @param instruction YAML 中的 instruction 值
     * @param agentName   关联的 Agent 名称（仅用于日志）
     * @return 解析后的系统提示词文本
     * @throws AgentCompileException 文件不存在或读取失败
     */
    public static String resolve(String instruction, String agentName) {
        if (instruction == null || instruction.isBlank()) {
            return instruction;
        }

        String trimmed = instruction.trim();

        if (trimmed.startsWith(CLASSPATH_PREFIX)) {
            String resourcePath = trimmed.substring(CLASSPATH_PREFIX.length()).trim();
            return loadFromClasspath(resourcePath, agentName);
        }

        if (trimmed.startsWith(FILE_PREFIX)) {
            String filePath = trimmed.substring(FILE_PREFIX.length()).trim();
            return loadFromFileSystem(filePath, agentName);
        }

        // 内联文本，原样返回
        return instruction;
    }

    private static String loadFromClasspath(String resourcePath, String agentName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = InstructionResolver.class.getClassLoader();
        }

        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new AgentCompileException(
                        "Agent [" + agentName + "] 的 instruction 引用了不存在的 classpath 资源: "
                                + resourcePath + "。请确认文件在 src/main/resources/" + resourcePath + " 下。");
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.info("Agent [{}] instruction 从 classpath 加载成功: {} ({} 字符)",
                    agentName, resourcePath, content.length());
            return content;
        } catch (IOException e) {
            throw new AgentCompileException(
                    "Agent [" + agentName + "] 读取 classpath 资源失败: " + resourcePath, e);
        }
    }

    private static String loadFromFileSystem(String filePath, String agentName) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new AgentCompileException(
                    "Agent [" + agentName + "] 的 instruction 引用了不存在的文件: " + filePath);
        }
        if (!Files.isReadable(path)) {
            throw new AgentCompileException(
                    "Agent [" + agentName + "] 的 instruction 引用的文件不可读: " + filePath);
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            log.info("Agent [{}] instruction 从文件系统加载成功: {} ({} 字符)",
                    agentName, filePath, content.length());
            return content;
        } catch (IOException e) {
            throw new AgentCompileException(
                    "Agent [" + agentName + "] 读取文件失败: " + filePath, e);
        }
    }
}
