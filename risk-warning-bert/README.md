# BERT 与分类服务本地说明

## 分类 checkpoint

分类服务默认从 `classify-service/checkpoints/best_model.pt` 读取训练权重。该目录受 Git 忽略规则保护，克隆仓库后默认不存在，不能把它当作已随源码交付的依赖。

如已从团队受控制品渠道取得与 `MultiTaskClassifier` 兼容的训练权重，在启动前设置路径：

```powershell
$env:CLASSIFIER_MODEL_PATH = 'D:\model-artifacts\best_model.pt'
```

当前仓库未提供训练脚本、训练数据或 checkpoint 分发机制。需要重新训练或取得权重时，应向模型负责人获取对应制品及其训练版本；不得把私有 checkpoint 提交到仓库。

## 缺失 checkpoint 时的实际行为

`ModelLoader` 在默认文件不存在时仍会构造基础模型和随机初始化的分类头。因此服务可能显示 `model_loaded=true` 并返回分类结果，但这些结果不代表经过训练的分类能力，不能用于验收或业务判断。

基础模型或预处理器无法加载时，`bert_service.py` 会把分类加载器置为 `null`；`/classify` 和 `/classify/batch` 会返回 HTTP 500 与“分类模型未加载”。健康检查只说明加载器是否存在，不证明训练 checkpoint 已加载。

## 测试约定

Java 新链单元测试应 Stub `ClassifierClient` 和 `VectorizationClient`，不得依赖私有 checkpoint、模型下载或完整 PG、ES、Kafka 环境。完整模型服务验收应使用受控 checkpoint 和固定样本另行执行。
