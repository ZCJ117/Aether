# AI Agent Scaffold Lite

基于 Spring Boot 和 Google ADK 的轻量级 AI Agent 脚手架，支持配置化定义智能体和工作流，集成 Spring AI、LangChain4j 和 MCP 工具协议。

## 项目简介

AI Agent Scaffold Lite 是一个企业级 AI Agent 开发脚手架，采用 DDD 架构设计，提供完整的智能体配置、工作流编排、工具集成和会话管理能力。项目支持多种智能体类型（单一、循环、并行、顺序）和灵活的配置方式，可快速构建复杂的 AI 应用管道。

## 核心特性

- **配置化智能体定义**：通过 YAML 配置文件定义智能体属性、指令、输出键和工具依赖
- **多样化工作流编排**：支持顺序（Sequential）、并行（Parallel）、循环（Loop）三种工作流模式
- **多模态能力支持**：集成文本、图像、文件等多种输入类型处理
- **MCP 工具集成**：内置 SSE 和本地 MCP 工具支持，可扩展第三方工具服务
- **技能库管理**：提供可复用的技能模板，支持 PDF 处理、系统信息获取等场景
- **会话状态管理**：完整的用户会话管理，支持流式响应和同步调用
- **模块化架构**：采用 DDD 分层设计，模块职责清晰，易于扩展和维护

## 技术栈

- **后端框架**：Spring Boot 3.4.3、Java 17
- **AI 框架**：Spring AI 1.1.0-M3、Google ADK 0.5.0、LangChain4j 1.4.0
- **数据库**：MySQL 8.0、MyBatis 3.0.4、HikariCP
- **工具协议**：MCP（Model Context Protocol）、SSE（Server-Sent Events）
- **构建工具**：Maven 3.0、Docker
- **其他组件**：Guava、FastJSON、Commons Lang3、JWT

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Docker（可选）

## 项目结构

```
ai-agent-scaffold-lite/
├── ai-agent-scaffold-lite-api/          # API 接口定义
├── ai-agent-scaffold-lite-app/          # 应用启动模块
│   ├── src/main/java/cn/bugstack/ai/   # 启动类、配置类
│   ├── src/main/resources/             # 配置文件
│   │   ├── agent/                      # 智能体配置
│   │   │   ├── demo.yml               # 示例配置
│   │   │   ├── only-one-agent.yml     # 单一智能体配置
│   │   │   ├── parallel_research_app.yml # 并行研究配置
│   │   │   └── skills/                # 技能库
│   │   └── application-*.yml          # 环境配置
├── ai-agent-scaffold-lite-domain/      # 领域模型
│   ├── src/main/java/cn/bugstack/ai/domain/agent/
│   │   ├── model/                     # 领域对象
│   │   ├── service/                   # 领域服务
│   │   │   ├── armory/               # 智能体装配服务
│   │   │   │   ├── matter/           # 工具、技能实现
│   │   │   │   ├── node/             # 工作流节点
│   │   │   │   └── factory/          # 工厂类
│   │   │   └── chat/                 # 会话服务
├── ai-agent-scaffold-lite-infrastructure/ # 基础设施
├── ai-agent-scaffold-lite-trigger/     # 触发器模块
└── ai-agent-scaffold-lite-types/       # 类型定义
```

通过ai.agent.config.tables 中的YAML配置定义智能体的ID、名称、描述、指令、输出键和工具依赖等属性，
支持单一智能体、循环智能体、并行智能体和顺序智能体四种类型。每个智能体可以配置不同的输入输出参数和工具调用方式，满足不同场景的需求。