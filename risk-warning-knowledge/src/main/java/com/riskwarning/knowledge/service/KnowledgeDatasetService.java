package com.riskwarning.knowledge.service;

import com.riskwarning.common.dto.scoring.ScoringDataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException;

/** 版本化评分依据的 Knowledge 服务入口；读取与校验逻辑也供离线发布校验使用。 */
@Service
@ConditionalOnProperty(prefix = "knowledge.dataset", name = "enabled", havingValue = "true")
public class KnowledgeDatasetService extends ScoringDataset {
    public KnowledgeDatasetService(@Value("${knowledge.dataset.directory:documents/data}") String directory) throws IOException {
        super(directory);
    }
}
