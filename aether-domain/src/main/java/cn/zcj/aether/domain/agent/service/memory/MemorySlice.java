package cn.zcj.aether.domain.agent.service.memory;

import java.util.*;

/**
 * 跨作用域只读视图。
 * 灵感来源：CrewAI MemorySlice（跨作用域记忆联合视图）。
 *
 * 将多个 MemoryScope 的记忆合并为一个只读视图，
 * 用于跨团队/跨 Agent 的知识共享场景。
 *
 * 使用示例：
 * <pre>
 * MemorySlice slice = new MemorySlice()
 *     .include(MemoryScope.global().subscope("crew/research"))
 *     .include(MemoryScope.global().subscope("crew/engineering"))
 *     .exclude(MemoryScope.global().subscope("crew/research/secrets"));
 *
 * // slice.getScopes() → 返回要搜索的作用域列表
 * // slice.isPrivateExcluded() → 检查是否排除了私有记忆
 * </pre>
 */
public class MemorySlice {

    /** 包含的作用域 */
    private final Set<MemoryScope> includes = new LinkedHashSet<>();

    /** 排除的作用域（优先级高于 includes） */
    private final Set<MemoryScope> excludes = new LinkedHashSet<>();

    /** 是否排除私密记忆 */
    private boolean excludePrivate = true;

    public MemorySlice include(MemoryScope scope) {
        includes.add(scope);
        return this;
    }

    public MemorySlice include(String path) {
        includes.add(new MemoryScope(path, false));
        return this;
    }

    public MemorySlice exclude(MemoryScope scope) {
        excludes.add(scope);
        return this;
    }

    public MemorySlice excludePrivate(boolean excludePrivate) {
        this.excludePrivate = excludePrivate;
        return this;
    }

    /** 获取有效的搜索作用域列表（includes - excludes） */
    public List<MemoryScope> getScopes() {
        List<MemoryScope> result = new ArrayList<>();
        for (MemoryScope include : includes) {
            boolean excluded = false;
            for (MemoryScope exclude : excludes) {
                if (include.equals(exclude) || exclude.contains(include) || include.contains(exclude)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                result.add(include);
            }
        }
        return result;
    }

    /** 是否排除私密记忆 */
    public boolean isPrivateExcluded() {
        return excludePrivate;
    }

    /** 获取所有包含的作用域（未过滤） */
    public Set<MemoryScope> getIncludes() {
        return Collections.unmodifiableSet(includes);
    }

    /** 获取所有排除的作用域 */
    public Set<MemoryScope> getExcludes() {
        return Collections.unmodifiableSet(excludes);
    }
}
