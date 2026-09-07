package com.riskwarning.report.service;

public interface AssessmentService {

    public void aggregateInformation(Long userId, Long projectId, Long assessmentId, String analysisRunId);

}
