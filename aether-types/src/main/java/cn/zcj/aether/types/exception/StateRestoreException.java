package cn.zcj.aether.types.exception;

/**
 * H5-步骤6: 状态恢复异常。
 *
 * <p>当 {@code loadState} 反序列化失败、槽位缺失或数据不完整时抛出。
 * 对齐 autogen 的"恢复失败要响亮地失败"语义——不带病恢复。
 *
 * <p>调用方应捕获此异常并做出明确响应（如拒绝会话恢复、返回明确错误），
 * 而非静默降级为新会话。
 */
public class StateRestoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 缺失的字段名 */
    private final String missingField;

    public StateRestoreException(String message) {
        super(message);
        this.missingField = null;
    }

    public StateRestoreException(String message, Throwable cause) {
        super(message, cause);
        this.missingField = null;
    }

    public StateRestoreException(String message, String missingField) {
        super(message);
        this.missingField = missingField;
    }

    public String getMissingField() {
        return missingField;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StateRestoreException{");
        sb.append("message='").append(getMessage()).append('\'');
        if (missingField != null) {
            sb.append(", missingField='").append(missingField).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
