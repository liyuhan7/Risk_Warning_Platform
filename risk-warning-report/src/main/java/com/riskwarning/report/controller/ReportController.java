package com.riskwarning.report.controller;

import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.result.Result;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/")
public class ReportController {


    @Autowired
    private ReportService reportService;

    @Autowired
    private AssessmentRepository assessmentRepository;


    @GetMapping("/indicatorResult/{assessmentId}")
    public Result reportIndicatorResult(@PathVariable Long assessmentId) {
        Assessment assessment=assessmentRepository.findById(assessmentId).orElse(null);
        return Result.success(reportService.assembleIndicatorResult(assessment));
    }

    @GetMapping("/risk")
    public Result reportRisk(@RequestParam Long assessmentId) {
        return Result.success(reportService.assembleRisk(assessmentId));
    }

    @GetMapping("/general/{assessmentId}")
    public Result reportGeneral(@PathVariable Long assessmentId) {
        Assessment assessment=assessmentRepository.findById(assessmentId).orElse(null);
        return Result.success(reportService.assembleGeneral(assessment));
    }

}
