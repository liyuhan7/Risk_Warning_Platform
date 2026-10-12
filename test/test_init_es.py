# -*- coding: utf-8 -*-
"""init_es mapping 策略的纯函数测试，不连接 Elasticsearch。"""
import copy
import importlib.util
import unittest
from unittest.mock import patch
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("init_es.py")
SPEC = importlib.util.spec_from_file_location("init_es", MODULE_PATH)
init_es = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(init_es)


def mapping_fixture(analyzer="ik_max_word"):
    return {
        index_name: {
            "mappings": {
                "properties": {
                    "retrievalTextIk": {"type": "text", "analyzer": analyzer},
                    "unchanged": {"type": "text", "analyzer": "ik_max_word"},
                }
            }
        }
        for index_name in init_es.ALLOWED_INDICES
    }


class FreshInitializationTest(unittest.TestCase):
    def test_knowledge_seed_sources_are_current_business_libraries(self):
        self.assertEqual("documents/data/indicator.json", init_es.INDEX_SEED_FILES["t_indicator"])
        self.assertEqual("documents/data/regulation.json", init_es.INDEX_SEED_FILES["t_regulation"])

    def test_existing_index_is_never_overwritten_or_seeded(self):
        with patch.object(init_es, "http", return_value=(200, "")) as http, patch.object(init_es, "bulk_import") as seed:
            init_es.initialize_missing_indices({"t_indicator": {}})
            self.assertEqual(["HEAD"], [call.args[0] for call in http.call_args_list])
            seed.assert_not_called()

    def test_missing_knowledge_index_is_created_and_seeded(self):
        with patch.object(init_es, "http", side_effect=[(404, ""), (200, "{}")]) as http, patch.object(init_es, "bulk_import") as seed:
            init_es.initialize_missing_indices({"t_indicator": {}})
            self.assertEqual(["HEAD", "PUT"], [call.args[0] for call in http.call_args_list])
            seed.assert_called_once_with("t_indicator", init_es.INDEX_SEED_FILES["t_indicator"])

    def test_missing_business_index_starts_empty(self):
        with patch.object(init_es, "http", side_effect=[(404, ""), (200, "{}")]), patch.object(init_es, "bulk_import") as seed:
            init_es.initialize_missing_indices({"t_behavior": {}})
            seed.assert_not_called()

    def test_connectivity_failure_stops_before_creation(self):
        with patch.object(init_es, "http", return_value=(503, "unavailable")) as http:
            with self.assertRaises(RuntimeError):
                init_es.initialize_missing_indices({"t_indicator": {}})
            self.assertEqual(1, http.call_count)


class ApplyRetrievalAnalyzerTest(unittest.TestCase):
    def test_default_policy_preserves_ik_for_both_indices(self):
        source = mapping_fixture()

        transformed = init_es.apply_retrieval_analyzer(
            source, init_es.DEFAULT_ANALYZER
        )

        for index_name in init_es.ALLOWED_INDICES:
            field = transformed[index_name]["mappings"]["properties"]["retrievalTextIk"]
            self.assertEqual(init_es.DEFAULT_ANALYZER, field["analyzer"])

    def test_explicit_fallback_changes_only_retrieval_field(self):
        source = mapping_fixture()
        original = copy.deepcopy(source)

        transformed = init_es.apply_retrieval_analyzer(
            source, init_es.FALLBACK_ANALYZER
        )

        self.assertEqual(original, source)
        for index_name in init_es.ALLOWED_INDICES:
            properties = transformed[index_name]["mappings"]["properties"]
            self.assertEqual(init_es.FALLBACK_ANALYZER, properties["retrievalTextIk"]["analyzer"])
            self.assertEqual("ik_max_word", properties["unchanged"]["analyzer"])

    def test_missing_retrieval_field_is_rejected(self):
        source = mapping_fixture()
        del source["t_regulation"]["mappings"]["properties"]["retrievalTextIk"]

        with self.assertRaisesRegex(RuntimeError, "t_regulation.retrievalTextIk"):
            init_es.apply_retrieval_analyzer(source, init_es.DEFAULT_ANALYZER)


class AssertRetrievalAnalyzerTest(unittest.TestCase):
    def test_read_back_mapping_matches_policy_for_both_indices(self):
        response = mapping_fixture(init_es.FALLBACK_ANALYZER)

        for index_name in init_es.ALLOWED_INDICES:
            init_es.assert_retrieval_analyzer(
                index_name, response, init_es.FALLBACK_ANALYZER
            )

    def test_analyzer_mismatch_is_rejected(self):
        response = mapping_fixture(init_es.DEFAULT_ANALYZER)

        with self.assertRaisesRegex(RuntimeError, "预期 'standard'"):
            init_es.assert_retrieval_analyzer(
                "t_indicator", response, init_es.FALLBACK_ANALYZER
            )

    def test_missing_analyzer_is_rejected(self):
        response = mapping_fixture()
        del response["t_indicator"]["mappings"]["properties"]["retrievalTextIk"]["analyzer"]

        with self.assertRaisesRegex(RuntimeError, "缺少 retrievalTextIk.analyzer"):
            init_es.assert_retrieval_analyzer(
                "t_indicator", response, init_es.DEFAULT_ANALYZER
            )


if __name__ == "__main__":
    unittest.main()
