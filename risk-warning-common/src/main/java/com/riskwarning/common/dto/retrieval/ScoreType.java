package com.riskwarning.common.dto.retrieval;

/** COSINE_SIMILARITY 为 (1+cosine)/2；HYBRID 为等权 dense 与归一化 BM25。 */
public enum ScoreType { COSINE_SIMILARITY, HYBRID }
