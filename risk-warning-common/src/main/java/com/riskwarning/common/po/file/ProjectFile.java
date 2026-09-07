package com.riskwarning.common.po.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 上传文件记录，一文件一行（P0-11 决议 3.1）。
 * id 即 sourceDocumentId 锚点，指向唯一物理文件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_project_file")
public class ProjectFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long userId;

    /** 新分析链使用的评估归属；历史记录迁移后允许为空。 */
    @Column(name = "assessment_id")
    private Long assessmentId;

    private String filePath;

    /** 用户上传时的原始文件名；历史记录允许为空。 */
    @Column(name = "original_file_name", length = 512)
    private String originalFileName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
