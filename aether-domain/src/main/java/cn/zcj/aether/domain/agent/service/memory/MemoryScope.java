package cn.zcj.aether.domain.agent.service.memory;

import java.util.List;
import java.util.ArrayList;

/**
 * 记忆作用域 —— 层级命名空间隔离。
 * 灵感来源：CrewAI MemoryScope（层级隔离）。
 *
 * 作用域路径示例：
 *   ""              — 全局作用域
 *   "crew/research" — 研究团队作用域
 *   "agent/analyst" — 分析师 Agent 私有域
 *   "user/alice"    — 用户 Alice 的私有域
 */
public record MemoryScope(String path, boolean isPrivate) {

    public static MemoryScope global() {
        return new MemoryScope("", false);
    }

    /** 创建子作用域 */
    public MemoryScope subscope(String name) {
        String newPath = path.isEmpty() ? name : path + "/" + name;
        return new MemoryScope(newPath, this.isPrivate);
    }

    /** 判断另一个作用域是否为本作用域的后代 */
    public boolean contains(MemoryScope other) {
        return other.path.startsWith(this.path);
    }

    /** 所有祖先路径（包含自身） */
    public List<String> ancestorPaths() {
        List<String> result = new ArrayList<>();
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("/");
            sb.append(part);
            result.add(sb.toString());
        }
        return result;
    }
}
