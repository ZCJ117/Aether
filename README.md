# AI Agent Scaffold Lite

## 项目概述

AI Agent Scaffold Lite 是一个基于 Spring Boot 的轻量级 AI 智能体脚手架项目，旨在帮助开发者快速构建、部署和管理智能体应用。项目采用模块化架构设计，集成了多种 AI 框架和工具，支持多智能体配置、技能扩展、会话管理以及流式对话等功能。通过本项目，您可以轻松搭建具备智能对话、任务处理和数据交互能力的 AI 应用系统。

## 核心功能

- **多智能体配置**：支持在配置文件中定义多个智能体，每个智能体可独立配置模型、工具和指令。
- **技能工具集成**：内置丰富的技能工具（如 PDF 处理、网络搜索等），并支持自定义技能扩展。
- **会话管理**：提供完整的会话生命周期管理，包括会话创建、消息历史记录和上下文维护。
- **流式对话**：支持 Server-Sent Events (SSE) 流式响应，实现实时对话体验。
- **RESTful API**：提供标准的 HTTP API 接口，便于第三方系统集成。
- **模块化架构**：采用领域驱动设计（DDD）思想，代码结构清晰，易于维护和扩展。
- **容器化部署**：支持 Docker 容器化部署，提供一键构建和运行脚本。

## 技术栈

### 后端框架
- **Spring Boot 3.4.3**：提供企业级应用开发基础框架
- **Spring AI**：用于集成大语言模型和 AI 功能
- **LangChain4j**：构建链式 AI 应用的 Java 库
- **Google ADK (Agent Development Kit)**：Google 智能体开发套件
- **MyBatis**：持久层框架，支持数据库操作
- **MySQL**：关系型数据库，用于存储配置和会话数据

### 工具与库
- **Guava**：Google 核心 Java 库，提供集合、缓存等工具
- **FastJSON**：高性能 JSON 处理库
- **Apache Commons Lang3**：Apache 通用工具库
- **JJWT**：JSON Web Token 生成与验证
- **Java JWT**：Auth0 提供的 JWT 库

### 开发与部署
- **Maven**：项目构建和依赖管理
- **Docker**：应用容器化部署
- **Java 17**：运行环境

## 环境要求

在开始之前，请确保您的开发环境满足以下要求：

- **JDK 17** 或更高版本
- **Maven 3.6+** 用于项目构建
- **MySQL 8.0+** 数据库服务
- **Docker**（可选，用于容器化部署）
- **Git** 用于版本控制

## 安装与配置

### 1. 克隆项目

```bash
git clone https://github.com/fuzhengwei/ai-agent-scaffold-lite.git
cd ai-agent-scaffold-lite
```

### 2. 数据库配置

1. 创建 MySQL 数据库：
   ```sql
   CREATE DATABASE xfg_frame_archetype CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

2. 导入数据库脚本：
   ```bash
   mysql -u root -p xfg_frame_archetype < docs/dev-ops/mysql/sql/xfg-frame-archetype.sql
   ```

3. 修改数据库连接配置：
   编辑 `ai-agent-scaffold-lite-app/src/main/resources/application-dev.yml` 文件，更新以下配置：
   ```yaml
   spring:
     datasource:
       username: your_username
       password: your_password
       url: jdbc:mysql://your_host:3306/xfg_frame_archetype?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=UTC&useSSL=true
   ```

### 3. AI 模型配置

1. 编辑智能体配置文件 `ai-agent-scaffold-lite-app/src/main/resources/agent/only-one-agent.yml`：
   ```yaml
   ai:
     agent:
       config:
         tables:
           testAgent03:
             app-name: testAgent03
             agent:
               agent-id: 100003
               agent-name: 单一智能体
               agent-desc: 单一智能体
             module:
               ai-api:
                 base-url: https://your-ai-api-endpoint
                 api-key: your-api-key
                 completions-path: v1/chat/completions
                 embeddings-path: v1/embeddings
   ```

   > **注意**：请替换为您的 AI 模型服务端点和 API 密钥。项目支持多种 AI 模型服务，具体配置请参考相关文档。

2. 配置工具集成：
   在同一个配置文件中，可以配置 MCP 工具（如百度搜索）和本地技能工具：
   ```yaml
   tool-mcp-list:
     - sse:
         name: baidu-search
         base-uri: http://appbuilder.baidu.com/v2/ai_search/mcp/
         sse-endpoint: sse?api_key=your-baidu-api-key
         request-timeout: 500000
   tool-skills-list:
     - type: resource
       path: agent/skills
   ```

### 4. 项目构建

```bash
# 进入项目根目录
cd ai-agent-scaffold-lite

# 编译项目
mvn clean compile

# 打包应用
mvn clean package -DskipTests
```

### 5. 运行应用

#### 方式一：使用 Maven 直接运行

```bash
mvn spring-boot:run -pl ai-agent-scaffold-lite-app
```

应用启动后，默认端口为 `8091`。

#### 方式二：使用 Docker 运行

1. 构建 Docker 镜像：
   ```bash
   cd ai-agent-scaffold-lite-app
   ./build.sh
   ```

2. 运行容器：
   ```bash
   docker run -d -p 8091:8091 --name ai-agent-app system/ai-agent-scaffold-lite-app:1.0
   ```

## 使用方法

### API 接口说明

项目提供以下 RESTful API 接口：

#### 1. 查询智能体配置列表

- **接口**：`GET /api/v1/query_ai_agent_config_list`
- **用途**：获取已配置的智能体列表
- **示例**：
  ```bash
  curl -X GET "http://localhost:8091/api/v1/query_ai_agent_config_list"
  ```
- **响应**：
  ```json
  {
    "code": "0000",
    "info": "成功",
    "data": [
      {
        "agentId": "100003",
        "agentName": "单一智能体",
        "agentDesc": "单一智能体"
      }
    ]
  }
  ```

#### 2. 创建会话

- **接口**：`POST /api/v1/create_session`
- **用途**：创建新的对话会话
- **请求参数**：
  ```json
  {
    "agentId": "100003",
    "userId": "user001"
  }
  ```
- **示例**：
  ```bash
  curl -X POST "http://localhost:8091/api/v1/create_session" \
    -H "Content-Type: application/json" \
    -d '{"agentId":"100003","userId":"user001"}'
  ```
- **响应**：
  ```json
  {
    "code": "0000",
    "info": "成功",
    "data": {
      "sessionId": "session_123456"
    }
  }
  ```

#### 3. 智能体对话

- **接口**：`POST /api/v1/chat`
- **用途**：与智能体进行对话
- **请求参数**：
  ```json
  {
    "agentId": "100003",
    "userId": "user001",
    "sessionId": "session_123456",
    "message": "你好，请介绍一下这个项目"
  }
  ```
- **示例**：
  ```bash
  curl -X POST "http://localhost:8091/api/v1/chat" \
    -H "Content-Type: application/json" \
    -d '{"agentId":"100003","userId":"user001","sessionId":"session_123456","message":"你好，请介绍一下这个项目"}'
  ```
- **响应**：
  ```json
  {
    "code": "0000",
    "info": "成功",
    "data": {
      "content": "AI Agent Scaffold Lite 是一个基于 Spring Boot 的轻量级 AI 智能体脚手架项目..."
    }
  }
  ```

#### 4. 流式对话

- **接口**：`POST /api/v1/chat_stream`
- **用途**：与智能体进行流式对话（Server-Sent Events）
- **示例**：
  ```bash
  curl -X POST "http://localhost:8091/api/v1/chat_stream" \
    -H "Content-Type: application/json" \
    -d '{"agentId":"100003","userId":"user001","sessionId":"session_123456","message":"请详细说明项目架构"}' \
    -H "Accept: text/event-stream"
  ```

### 配置多个智能体

您可以通过编辑配置文件来定义多个智能体：

1. 创建新的智能体配置文件，例如 `my-agent.yml`
2. 在 `application-dev.yml` 中导入配置文件：
   ```yaml
   spring:
     config:
       import:
         - classpath:agent/my-agent.yml
   ```
3. 配置智能体参数，包括智能体名称、描述、指令和工具配置

### 自定义技能开发

项目支持自定义技能扩展，您可以通过以下步骤添加新技能：

1. 在 `ai-agent-scaffold-lite-app/src/main/resources/agent/skills/` 目录下创建技能文件夹
2. 按照现有技能模板编写技能描述文件 `SKILL.md`
3. 在智能体配置文件中引用技能：
   ```yaml
   tool-skills-list:
     - type: resource
       path: agent/skills/your-skill-name
   ```

## 项目结构说明

```
ai-agent-scaffold-lite/
├── ai-agent-scaffold-lite-api/          # API 接口定义模块
│   └── src/main/java/cn/bugstack/ai/api/
│       ├── dto/                         # 数据传输对象
│       ├── response/                    # 统一响应结构
│       └── IAgentService.java           # 服务接口定义
├── ai-agent-scaffold-lite-app/          # 应用主模块
│   ├── src/main/java/cn/bugstack/ai/
│   │   ├── config/                      # 配置类
│   │   └── Application.java             # 应用启动类
│   ├── src/main/resources/
│   │   ├── agent/                       # 智能体配置
│   │   │   ├── skills/                  # 技能工具
│   │   │   │   ├── pdf/                 # PDF 处理技能
│   │   │   │   └── battle-plan/         # 作战计划技能
│   │   │   └── *.yml                    # 智能体配置文件
│   │   ├── application.yml              # 主配置文件
│   │   └── application-dev.yml          # 开发环境配置
│   └── Dockerfile                       # Docker 构建文件
├── ai-agent-scaffold-lite-domain/       # 领域模块
├── ai-agent-scaffold-lite-infrastructure/ # 基础设施模块
│   └── src/main/java/cn/bugstack/ai/infrastructure/
│       ├── dao/                         # 数据访问对象
│       ├── gateway/                     # 网关适配器
│       └── redis/                       # Redis 配置
├── ai-agent-scaffold-lite-trigger/      # 触发器模块
│   └── src/main/java/cn/bugstack/ai/trigger/
│       └── http/                        # HTTP 控制器
├── ai-agent-scaffold-lite-types/        # 类型定义模块
│   └── src/main/java/cn/bugstack/ai/types/
│       ├── common/                      # 通用常量
│       ├── enums/                       # 枚举类型
│       └── exception/                   # 异常定义
├── docs/                                # 文档目录
│   ├── dev-ops/                         # 运维文档
│   │   ├── api/                         # API 文档
│   │   ├── app/                         # 应用部署
│   │   ├── mysql/                       # 数据库脚本
│   │   └── nginx/                       # Nginx 配置
│   └── prompt/                          # 提示词文档
└── pom.xml                              # 项目根 POM
```

### 模块职责

- **api 模块**：定义服务接口和 DTO，保持接口的稳定性和兼容性
- **app 模块**：应用入口，包含配置、启动类和资源文件
- **domain 模块**：业务领域模型和核心逻辑（当前为空，可根据需要扩展）
- **infrastructure 模块**：基础设施实现，包括数据访问、外部服务集成
- **trigger 模块**：触发器实现，如 HTTP 控制器、定时任务等
- **types 模块**：通用类型定义，如常量、枚举、异常等

## 贡献指南

我们欢迎任何形式的贡献，包括但不限于：

- **报告问题**：在 GitHub Issues 中报告发现的 Bug 或提出改进建议
- **功能开发**：实现新功能或优化现有功能
- **文档完善**：改进文档内容，增加示例代码
- **代码优化**：重构代码，提升性能和可读性

### 贡献流程

1. **Fork 仓库**：点击 GitHub 页面右上角的 Fork 按钮
2. **克隆仓库**：
   ```bash
   git clone https://github.com/your-username/ai-agent-scaffold-lite.git
   ```
3. **创建分支**：
   ```bash
   git checkout -b feature/your-feature-name
   ```
4. **提交更改**：进行代码修改并提交
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   ```
5. **推送分支**：
   ```bash
   git push origin feature/your-feature-name
   ```
6. **创建 Pull Request**：在 GitHub 上创建 Pull Request，描述您的修改内容

### 代码规范

- 遵循 Java 编码规范，使用 4 个空格缩进
- 类名使用大驼峰命名法，方法名和变量名使用小驼峰命名法
- 添加必要的注释，特别是公共方法和复杂逻辑
- 确保代码通过编译和单元测试

## 许可证信息

本项目采用 Apache License 2.0 开源协议，详情请参阅 [LICENSE](LICENSE) 文件。

```
Copyright 2024-2025 xiaofuge

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 联系方式

如果您在使用过程中遇到任何问题，或有任何建议，欢迎通过以下方式联系我们：

- **作者**：小傅哥 (xiaofuge)
- **邮箱**：184172133@qq.com
- **GitHub**：[https://github.com/fuzhengwei](https://github.com/fuzhengwei)
- **组织**：[bugstack.cn](https://bugstack.cn)

## 更新日志

### v1.0 (2024-xx-xx)
- 项目初始版本发布
- 支持多智能体配置和管理
- 集成 Spring AI 和 LangChain4j 框架
- 提供完整的 RESTful API 接口
- 支持 Docker 容器化部署
- 内置 PDF 处理和网络搜索等技能工具

---

**感谢您使用 AI Agent Scaffold Lite！希望本项目能为您的 AI 应用开发带来便利。**