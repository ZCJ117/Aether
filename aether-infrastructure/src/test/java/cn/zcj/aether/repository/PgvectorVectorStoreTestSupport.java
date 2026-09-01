package cn.zcj.aether.repository;

/** P1(4.2): IT 共用小工具 —— float[] → pgvector 字符串。 */
final class PgvectorVectorStoreTestSupport {

    private PgvectorVectorStoreTestSupport() {
    }

    static String toDbVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }
}
