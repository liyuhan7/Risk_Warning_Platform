"""AnalysisResult 1.1 结构契约，候选与证据归属由分析响应校验器验证。"""
import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]

class AnalysisResultContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads((ROOT / 'documents/contracts/analysis-result-1.1.schema.json').read_text(encoding='utf-8'))
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema)

    def sample(self):
        return dict(schemaVersion='1.1', id='a', assessmentId=1, analysisRunId='run',
                    behaviorId='b', indicatorId='i', evidenceIds=['e'], regulationIds=[],
                    applicable='APPLICABLE', complianceStatus='COMPLIANT', enterpriseFact='事实',
                    confidence=0.8, reasoning='说明', modelVersion='model', promptVersion='prompt',
                    analyzedAt='2026-09-30T12:00:00')

    def test_binary_false_and_range_zero(self):
        for extra in [dict(conditionMet=False), dict(observedValue=0, observedUnit='day')]:
            self.validator.validate(dict(self.sample(), **extra))

    def test_missing_evidence_unknown_conclusion_and_gap_are_rejected(self):
        for extra in [dict(evidenceIds=[]), dict(applicable='UNKNOWN'),
                      dict(complianceStatus='NON_COMPLIANT'), dict(gapValue=1),
                      dict(confidence=-0.1), dict(confidence=1.1), dict(schemaVersion='2.0')]:
            with self.subTest(extra=extra):
                self.assertTrue(list(self.validator.iter_errors(dict(self.sample(), **extra))))

    def test_range_unit_and_mutual_exclusion(self):
        for extra in [dict(observedValue=1), dict(observedValue=1, observedUnit=''),
                      dict(observedUnit='day'), dict(conditionMet=False, observedValue=1, observedUnit='day')]:
            with self.subTest(extra=extra):
                self.assertTrue(list(self.validator.iter_errors(dict(self.sample(), **extra))))

    def test_undecided_nullable_inputs_are_valid(self):
        for status in ['INSUFFICIENT_EVIDENCE', 'NEEDS_REVIEW']:
            self.validator.validate(dict(self.sample(), applicable='UNKNOWN', complianceStatus=status,
                                         conditionMet=None, observedValue=None, observedUnit=None))

    def test_model_response_schema_and_packaged_resource_agree(self):
        path = ROOT / 'documents/contracts/compliance-response-1.1.schema.json'
        resource = ROOT / 'risk-warning-processing/src/main/resources/analysis/compliance-response-1.1.schema.json'
        self.assertEqual(path.read_bytes(), resource.read_bytes())
        schema = json.loads(path.read_text(encoding='utf-8'))
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
        sample = self.sample()
        for field in ['id', 'assessmentId', 'analysisRunId', 'behaviorId', 'modelVersion', 'promptVersion', 'analyzedAt']:
            sample.pop(field)
        sample['conditionMet'] = False
        validator.validate(sample)
        self.assertTrue(list(validator.iter_errors(dict(sample, analysisRunId='forged'))))
        sample.pop('conditionMet')
        sample.update(indicatorId=None, applicable='NOT_APPLICABLE', complianceStatus='NO_APPLICABLE_CANDIDATE')
        validator.validate(sample)

if __name__ == '__main__':
    unittest.main()
