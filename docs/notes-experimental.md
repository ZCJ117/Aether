# 笔记包实验性说明

`aether-domain/.../notes` 当前是 **实验能力**：

- `ExternalNotes` 仅将 TODO/NOTE 落盘到 `.aether/notes/<sessionId>.json`，未提供跨实例同步、加密或审计；
- Agent 工厂会在创建 ReAct/PlanAct Agent 时注入 `ExternalNotes`，但没有强制启用工具的配置门控；
- 该包的 `todo_write` / `note_write` 工具适合单机开发和演示；多实例生产部署应先评估文件写入路径（NFS/PVC）或迁移到 PG/Redis；
- 本包不再有 `flushMemories()` 空操作入口；任务复盘由 `BackgroundReviewer` 输出后由调用方写入 `ExternalNotes`。

因此路线图 P2-2.3 将该包“显式标注实验性”，不承诺向后兼容。
