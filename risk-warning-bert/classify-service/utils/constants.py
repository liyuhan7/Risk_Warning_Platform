# 预定义的标签列表
PREDEFINED_TAGS = [
    # 财务管理类
    "财务指标", "资金使用", "财务报告", "成本控制", "收入增长",
    "流动性", "盈利能力", "偿债能力", "营运能力", "发展能力",
    # 业务运营类
    "合同签署", "交易管理", "投资管理", "市场营销", "产品销售",
    "运营效率", "客户关系管理", "供应商管理", "效率指标",
    # 人力资源类
    "人力资源", "人事管理", "薪酬管理", "员工发展", "组织效能",
    # 风险管控类
    "风险管理", "风险管控", "内部举报处理", "安全指标", "质量管控",
    # 合规治理类
    "法规遵循", "许可证管理", "内控制度执行", "审计管理",
    "信息披露", "股东大会", "治理结构",
    # 外部关系类
    "政府关系维护", "监管配合", "客户满意度", "市场表现",
    # 社会责任类
    "可持续发展", "ESG指标", "社会责任履行", "公益活动", "环境保护",
    # 创新发展类
    "技术创新", "产品创新", "数字化转型", "研发投入",
    # 制度建设类
    "合规培训", "制度建设", "流程优化", "标准化管理"
]

# 预定义的行业列表
PREDEFINED_INDUSTRIES = [
    # 金融业
    "银行业", "证券业", "保险业", "信托业", "基金业", "期货业", "金融租赁",
    # 制造业
    "汽车制造", "电子制造", "机械制造", "化工制造", "纺织制造", "食品制造",
    "医药制造", "钢铁制造", "有色金属", "建材制造",
    # 信息技术
    "软件开发", "互联网", "电信运营", "数据服务", "人工智能", "云计算",
    # 能源行业
    "石油天然气", "电力", "煤炭", "新能源", "核能", "水电",
    # 房地产建筑
    "房地产", "建筑工程", "装饰装修", "物业管理",
    # 零售贸易
    "零售业", "批发业", "电子商务", "连锁经营",
    # 交通运输
    "航空运输", "铁路运输", "公路运输", "水路运输", "物流仓储",
    # 医疗健康
    "医疗服务", "医疗器械", "生物技术", "健康管理",
    # 教育文化
    "教育服务", "文化传媒", "出版印刷", "广告营销",
    # 农林牧渔
    "农业", "林业", "牧业", "渔业", "农产品加工",
    # 公共服务
    "公共事业", "环保服务", "咨询服务", "法律服务", "会计服务",
    # 其他
    "综合类"
]

# 预定义的维度列表
DIMENSIONS = [
    "企业关联方风险", "产品合规风险", "劳务合规风险",
    "企业信用风险", "企业国际合作风险", "供应链风险"
]

# 预定义的类型列表
TYPE_LABELS = ["定性", "定量"]

# 输入类型
INPUT_TYPES = ["behavior", "indicator", "regulation"]

# 标签到索引的映射
TAG_TO_IDX = {tag: idx for idx, tag in enumerate(PREDEFINED_TAGS)}
IDX_TO_TAG = {idx: tag for idx, tag in enumerate(PREDEFINED_TAGS)}

# 行业到索引的映射
INDUSTRY_TO_IDX = {industry: idx for idx, industry in enumerate(PREDEFINED_INDUSTRIES)}
IDX_TO_INDUSTRY = {idx: industry for idx, industry in enumerate(PREDEFINED_INDUSTRIES)}

# 维度到索引的映射
DIMENSION_TO_IDX = {dim: idx for idx, dim in enumerate(DIMENSIONS)}
IDX_TO_DIMENSION = {idx: dim for idx, dim in enumerate(DIMENSIONS)}

# 类型到索引的映射
TYPE_TO_IDX = {"定性": 0, "定量": 1}
IDX_TO_TYPE = {0: "定性", 1: "定量"}

# 模型配置
MODEL_CONFIG = {
    "num_tags": len(PREDEFINED_TAGS),
    "num_industries": len(PREDEFINED_INDUSTRIES),
    "num_dimensions": len(DIMENSIONS),
    "num_types": len(TYPE_LABELS),
    "hidden_size": 768,  # BERT base的隐藏层大小
}

