"""
BERT 服务测试代码
测试分类功能和向量化功能
"""
import unittest
import json
import requests
import time
from pathlib import Path


class TestBERTService(unittest.TestCase):
    """BERT 服务测试类"""

    def setUp(self):
        """测试前准备"""
        self.base_url = "http://localhost:8000"
        self.timeout = 30

    def test_health_check(self):
        """测试健康检查接口"""
        response = requests.get(
            f"{self.base_url}/health",
            timeout=self.timeout
        )
        self.assertEqual(response.status_code, 200)

        data = response.json()
        self.assertEqual(data["status"], "ok")
        self.assertIn("bert_model_loaded", data)
        self.assertIn("classifier_model_loaded", data)

        print(f"✓ 健康检查通过 - BERT 模型：{data['bert_model_loaded']}, "
              f"分类模型：{data['classifier_model_loaded']}")

    def test_classify_health(self):
        """测试分类服务健康检查"""
        response = requests.get(
            f"{self.base_url}/classify/health",
            timeout=self.timeout
        )
        self.assertEqual(response.status_code, 200)

        data = response.json()
        self.assertEqual(data["status"], "ok")
        self.assertIn("model_loaded", data)

        print(f"✓ 分类服务健康检查通过 - 模型已加载：{data['model_loaded']}")

    def test_classify_single_behavior(self):
        """测试单个行为文本分类"""
        test_data = {
            "text": "企业未按时披露财务报告",
            "input_type": "behavior"
        }

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构（根据实际返回格式）
        self.assertIn("tags", result)
        self.assertIsInstance(result["tags"], list)
        self.assertIn("type", result)
        self.assertIsInstance(result["type"], str)
        self.assertIn("dimension", result)
        self.assertIsInstance(result["dimension"], str)

        print(f"✓ 行为分类测试通过:")
        print(f"  文本：{test_data['text']}")
        print(f"  标签：{result['tags']}")
        print(f"  类型：{result['type']}")
        print(f"  维度：{result['dimension']}")

    def test_classify_single_indicator(self):
        """测试单个指标文本分类"""
        test_data = {
            "text": "资产负债率",
            "input_type": "indicator"
        }

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构（指标应该有 industry 字段）
        self.assertIn("tags", result)
        self.assertIsInstance(result["tags"], list)
        self.assertIn("type", result)
        self.assertIn("dimension", result)
        self.assertIn("industry", result)
        self.assertIsInstance(result["industry"], list)

        print(f"✓ 指标分类测试通过:")
        print(f"  文本：{test_data['text']}")
        print(f"  标签：{result['tags']}")
        print(f"  类型：{result['type']}")
        print(f"  维度：{result['dimension']}")
        print(f"  行业：{result['industry']}")

    def test_classify_single_regulation(self):
        """测试单个法规文本分类"""
        test_data = {
            "text": "上市公司信息披露管理办法",
            "input_type": "regulation"
        }

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构（法规应该有 industry 字段）
        self.assertIn("tags", result)
        self.assertIn("type", result)
        self.assertIn("dimension", result)
        self.assertIn("industry", result)

        print(f"✓ 法规分类测试通过:")
        print(f"  文本：{test_data['text']}")
        print(f"  标签：{result['tags']}")
        print(f"  行业：{result['industry']}")

    def test_classify_invalid_input_type(self):
        """测试无效的 input_type"""
        test_data = {
            "text": "测试文本",
            "input_type": "invalid_type"
        }

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        # 应该返回 400 错误
        self.assertEqual(response.status_code, 400)
        print(f"✓ 无效 input_type 测试通过 - 正确返回错误")

    def test_classify_empty_text(self):
        """测试空文本"""
        test_data = {
            "text": "",
            "input_type": "behavior"
        }

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        # 应该返回 400 错误
        self.assertEqual(response.status_code, 400)
        print(f"✓ 空文本测试通过 - 正确返回错误")

    def test_classify_batch(self):
        """测试批量分类"""
        test_data = {
            "items": [
                {"text": "企业未按时披露财务报告", "input_type": "behavior"},
                {"text": "资产负债率", "input_type": "indicator"},
                {"text": "上市公司信息披露管理办法", "input_type": "regulation"}
            ]
        }

        response = requests.post(
            f"{self.base_url}/classify/batch",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构
        self.assertIn("results", result)
        self.assertIsInstance(result["results"], list)
        self.assertEqual(len(result["results"]), 3)

        print(f"✓ 批量分类测试通过:")
        for i, item_result in enumerate(result["results"]):
            print(f"  [{i+1}] 标签：{item_result['tags']}, 类型：{item_result['type']}")

    def test_vectorize_single(self):
        """测试单个文本向量化"""
        test_data = {
            "text": "这是一个测试文本"
        }

        response = requests.post(
            f"{self.base_url}/vectorize-single",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构
        self.assertIn("vector", result)
        self.assertIn("dimension", result)
        self.assertEqual(result["dimension"], 768)  # BERT base 的输出维度
        self.assertEqual(len(result["vector"]), 768)

        print(f"✓ 单文本向量化测试通过:")
        print(f"  向量维度：{result['dimension']}")
        print(f"  向量前 5 维：{result['vector'][:5]}")

    def test_encode_batch(self):
        """测试批量向量化"""
        test_data = {
            "texts": [
                "第一个测试文本",
                "第二个测试文本",
                "第三个测试文本"
            ]
        }

        response = requests.post(
            f"{self.base_url}/encode",
            json=test_data,
            timeout=self.timeout
        )

        self.assertEqual(response.status_code, 200)
        result = response.json()

        # 验证返回结果结构
        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 3)

        # 验证每个向量的维度
        for i, vector in enumerate(result):
            self.assertEqual(len(vector), 768)

        print(f"✓ 批量向量化测试通过:")
        print(f"  处理文本数：{len(result)}")
        print(f"  向量维度：{len(result[0])}")

    def test_missing_required_field(self):
        """测试缺少必需字段"""
        test_data = {}

        response = requests.post(
            f"{self.base_url}/classify",
            json=test_data,
            timeout=self.timeout
        )

        # 应该返回 400 错误
        self.assertEqual(response.status_code, 400)
        print(f"✓ 缺少必需字段测试通过 - 正确返回错误")


def run_tests():
    """运行所有测试"""
    print("=" * 60)
    print("BERT 服务分类功能测试")
    print("=" * 60)
    print()

    # 创建测试套件
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()

    # 添加测试
    suite.addTests(loader.loadTestsFromTestCase(TestBERTService))

    # 运行测试
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print()
    print("=" * 60)
    print(f"测试完成：{result.testsRun} 个测试")
    print(f"成功：{result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"失败：{len(result.failures)}")
    print(f"错误：{len(result.errors)}")
    print("=" * 60)

    return result.wasSuccessful()


if __name__ == '__main__':
    # 检查服务是否运行
    try:
        response = requests.get("http://localhost:8000/health", timeout=5)
        if response.status_code != 200:
            print("❌ 错误：BERT 服务未运行或无法访问")
            print("请先启动服务：python bert_service.py")
            exit(1)
    except requests.exceptions.ConnectionError:
        print("❌ 错误：BERT 服务未运行")
        print("请先启动服务：python bert_service.py")
        exit(1)

    # 运行测试
    success = run_tests()
    exit(0 if success else 1)
