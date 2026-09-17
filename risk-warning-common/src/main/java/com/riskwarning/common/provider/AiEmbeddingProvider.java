package com.riskwarning.common.provider;

import java.util.List;

/** 检索向量边界；输出按输入顺序排列，均为有限、非零的 L2 单位向量。 */
public interface AiEmbeddingProvider {
    List<List<Float>> embed(List<String> texts, EmbeddingRole role);
    String modelId();
    int dimension();
}
