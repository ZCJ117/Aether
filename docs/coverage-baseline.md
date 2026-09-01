# 覆盖率基线（P0 2.1）

> 生成命令：`mvn -B verify && python scripts/coverage-summary.py --write docs/coverage-baseline.md`

| 模块 | 行覆盖率 | 分支覆盖率 | 指令覆盖率 |
|------|---------:|-----------:|-----------:|
| aether-domain | 55.4% | 43.1% | 55.0% |
| aether-infrastructure | 41.4% | 33.3% | 43.3% |
| aether-trigger | 21.5% | 22.7% | 21.8% |
| aether-app | 51.7% | 28.7% | 57.2% |
| **全项目** | **51.4%** | **40.9%** | |

### domain 包级行覆盖率（盲区排序）

| 包 | 行覆盖率 |
|----|---------:|
| cn.zcj.aether.domain.agent.model.entity | 0.0% |
| cn.zcj.aether.domain.agent.model.valobj.enums | 0.0% |
| cn.zcj.aether.domain.agent.service.armory.factory | 0.0% |
| cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.impl | 0.0% |
| cn.zcj.aether.domain.agent.service.armory.matter.skills.impl | 0.0% |
| cn.zcj.aether.domain.agent.service.armory.node | 0.0% |
| cn.zcj.aether.domain.agent.service.armory.node.workflow | 0.0% |
| cn.zcj.aether.domain.agent.service.security | 0.0% |
| cn.zcj.aether.domain.agent.service.tool.python | 0.0% |
| cn.zcj.aether.domain.agent.service.agent | 1.3% |
| cn.zcj.aether.domain.agent.service.armory | 3.1% |
| cn.zcj.aether.domain.agent.service.notes | 5.9% |
