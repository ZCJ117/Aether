package cn.zcj.aether.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0(4.1) ToolRouter 单元测试 —— 确定性工具路由的选型正确性。
 */
class ToolRouterTest {

    private static EvalCase.ToolDef tool(String name, String desc) {
        EvalCase.ToolDef t = new EvalCase.ToolDef();
        t.name = name;
        t.description = desc;
        return t;
    }

    @Test
    void selectsSearchToolForWeatherQuery() {
        String chosen = ToolRouter.select("杭州今天天气怎么样？",
                List.of(
                        tool("baidu-search", "搜索互联网实时信息，例如天气、新闻、票价、航班动态"),
                        tool("execute_code", "在安全沙箱中执行 Python 代码，用于计算与数据处理")));
        assertEquals("baidu-search", chosen);
    }

    @Test
    void selectsCodeToolForComputation() {
        String chosen = ToolRouter.select("帮我用 Python 计算斐波那契数列前 20 项",
                List.of(
                        tool("baidu-search", "搜索互联网实时信息，例如天气、新闻、票价"),
                        tool("execute_code", "在安全沙箱中执行 Python 代码，用于计算与数据处理"),
                        tool("read_file", "读取文本文件内容，支持 txt json md")));
        assertEquals("execute_code", chosen);
    }

    @Test
    void latinWordsAlsoMatch() {
        String chosen = ToolRouter.select("请读取这个 docx 格式的简历文档",
                List.of(
                        tool("read_file", "读取文本文件内容，支持 txt json md"),
                        tool("read_document", "读取 Office 文档（docx pptx xlsx）内容并转为文本")));
        assertEquals("read_document", chosen);
    }

    @Test
    void tokenizeHandlesCjkBigrams() {
        var tokens = ToolRouter.tokenize("杭州天气");
        assertTrue(tokens.contains("杭州"));
        assertTrue(tokens.contains("天气"));
        assertTrue(tokens.contains("州天"));
    }
}
