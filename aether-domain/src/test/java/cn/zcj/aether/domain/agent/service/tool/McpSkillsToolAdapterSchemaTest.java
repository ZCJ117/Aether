package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.zcj.aether.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O9 — MCP/Skills 工具真实 JSON Schema 单元测试。
 *
 * 验证：
 * 1. McpToolAdapter 从 ToolCallback 的 ToolDefinition 提取真实 inputSchema（非硬编码空 schema）；
 * 2. SkillsToolAdapter 同样提取真实 schema（非固定 {"query":string}）；
 * 3. ToolDefinition 缺失 schema 时降级为空 schema 并不阻断（warn 降级分支）。
 */
class McpSkillsToolAdapterSchemaTest {

    private static final String REAL_SCHEMA_JSON = """
            {"type":"object","properties":{
                "path":{"type":"string","description":"文件路径"},
                "content":{"type":"string","description":"文件内容"}
            },"required":["path"]}
            """;

    private DefaultMcpClientFactory mcpClientFactory;
    private ToolSkillsCreateService skillsCreateService;

    @BeforeEach
    void setUp() {
        mcpClientFactory = mock(DefaultMcpClientFactory.class);
        skillsCreateService = mock(ToolSkillsCreateService.class);
    }

    private static ToolCallback callbackWithSchema(String schemaJson, String description) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("underlying-tool")
                        .description(description)
                        .inputSchema(schemaJson)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    @Test
    void mcpAdapterExposesRealInputSchema() throws Exception {
        ToolCallback callback = callbackWithSchema(REAL_SCHEMA_JSON, "读取工作区文件");
        TooMcpCreateService createService = mock(TooMcpCreateService.class);
        when(createService.buildToolCallback(any())).thenReturn(new ToolCallback[]{callback});
        when(mcpClientFactory.getTooMcpCreateService(any())).thenReturn(createService);

        McpToolAdapter adapter = new McpToolAdapter();
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "defaultMcpClientFactory", mcpClientFactory);

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse =
                new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName("fs-server");
        toolMcp.setSse(sse);

        Tool tool = adapter.adapt(toolMcp);

        Map<String, Object> schema = tool.inputSchema();
        assertNotNull(schema);
        // O9 核心：schema 非空且来自真实 ToolDefinition
        assertTrue(schema.get("properties") instanceof Map<?, ?> props && !props.isEmpty(),
                "MCP 工具 schema 不应再是硬编码空 properties: " + schema);
        assertTrue(schema.containsKey("required"), "required 字段应被保留");
        // 真实 description 透传
        assertEquals("读取工作区文件", tool.description());
    }

    @Test
    void skillsAdapterExposesRealInputSchema() throws Exception {
        ToolCallback callback = callbackWithSchema(REAL_SCHEMA_JSON, "搜索技能库");
        when(skillsCreateService.buildToolCallback(any())).thenReturn(new ToolCallback[]{callback});

        SkillsToolAdapter adapter = new SkillsToolAdapter();
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "toolSkillsCreateService", skillsCreateService);

        AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills = new AiAgentConfigTableVO.Module.ChatModel.ToolSkills();
        toolSkills.setType("directory");
        toolSkills.setPath("/skills/search");

        Tool tool = adapter.adapt(toolSkills);

        Map<String, Object> schema = tool.inputSchema();
        assertNotNull(schema);
        assertTrue(schema.get("properties") instanceof Map<?, ?> props && !props.isEmpty(),
                "Skills 工具 schema 不应再是固定占位: " + schema);
        assertEquals("搜索技能库", tool.description());
    }

    @Test
    void mcpAdapterFallsBackToEmptySchemaWhenDefinitionLacksIt() throws Exception {
        // ToolDefinition 无 schema（降级分支：不阻断、空 schema + 告警）
        ToolCallback callback = callbackWithSchema(null, null);
        TooMcpCreateService createService = mock(TooMcpCreateService.class);
        when(createService.buildToolCallback(any())).thenReturn(new ToolCallback[]{callback});
        when(mcpClientFactory.getTooMcpCreateService(any())).thenReturn(createService);

        McpToolAdapter adapter = new McpToolAdapter();
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "defaultMcpClientFactory", mcpClientFactory);

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse =
                new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName("no-schema-server");
        toolMcp.setSse(sse);

        Tool tool = adapter.adapt(toolMcp);

        Map<String, Object> schema = tool.inputSchema();
        assertNotNull(schema, "降级分支应返回非 null 空结构（不阻断注册）");
        assertEquals("object", schema.get("type"));
        assertTrue(schema.get("properties") instanceof Map<?, ?> props && props.isEmpty());
    }
}
