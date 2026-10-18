package com.lender.assistant.common.util;

/**
 * pgvector 文本字面量工具。
 *
 * 为什么放 common/util 而不是 rag 领域：float[] → "[0.1,...]" 是**数据库表示层**的关切
 * （MyBatis XML 无法调 Java，向量必须以字符串参数传入，见 §6.7），不是 RAG 语义关切；
 * rag 领域没有 util 子包，故按 §0.2 归 common/util。
 */
public final class PgVectorUtils {

    private PgVectorUtils() {
    }

    /** float[] → pgvector 文本字面量 "[0.1,0.2,...]" */
    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
