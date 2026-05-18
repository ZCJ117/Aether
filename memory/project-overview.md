---
name: 项目概述与技术栈
description: aether 项目的模块结构、技术栈和常用命令
type: reference
---

# 项目概述

aether 是企业级 AI Agent 脚手架，基于 Spring Boot 3.4.3 + 自研 AgentRuntime + DDD 分层架构。

## 技术栈

- **后端**: Java 17, Spring Boot 3.4.3, Maven 3.x, Spring AI 1.1.0-M3
- **Agent 引擎**: 自研 AgentRuntime（主循环 + 三层上下文压缩 + 并发安全工具编排 + 指数退避重试）
- **前端**: Vue 3 + Pinia + Vite（`docs/dev-ops/AIagent_frontend/`）
- **依赖**: Lombok, RxJava3, Jackson, Guava
- **容器**: Docker (openjdk:17-jdk-slim)

## 6 模块结构

| 模块 | 职责 |
|---|---|
| `aether-api` | 服务接口、DTO 定义 |
| `aether-app` | 启动引导、YAML 配置、HttpClientConfig |
| `aether-domain` | **核心**：Agent 运行时、编译器、上下文管理、工具系统、记忆系统 |
| `aether-infrastructure` | 基础设施适配器（会话存储等） |
| `aether-trigger` | REST 控制器（HTTP 入口） |
| `aether-types` | 枚举、异常、常量 |

## 常用命令

```bash
mvn clean install                                    # 全量构建
mvn clean compile -pl aether-domain -am     # 仅编译 domain 模块
mvn clean package -pl aether-app -am        # 打包可执行 JAR
cd aether-app && bash build.sh              # Docker 构建
```

## DDD 分层规则

- `trigger` → `api` → `domain`；`infrastructure` 实现 `domain` 的适配器端口
- `domain` 禁止从 `trigger` 或 `infrastructure` 导入
- JSON 解析统一使用 Jackson `ObjectMapper`
- ChatModel 在装配阶段动态注册，所有消费者必须 `@Lazy` 注入
