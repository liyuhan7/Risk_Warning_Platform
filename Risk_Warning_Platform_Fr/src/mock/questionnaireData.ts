import type { QuestionnaireForm } from '@/types/es_types'

/**
 * 模拟问卷数据
 */
export const mockQuestionnaire: QuestionnaireForm[] = [
  // ========== 产品合规风险 ==========
  {
    question_type: 'single_choice',
    question_text: '企业产品是否符合《产品质量法》第二十六条关于产品质量基本要求？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，完全符合', score_mapping: 10, is_default: false },
      { value: 'partial', label: '部分符合', score_mapping: 5, is_default: false },
      { value: 'no', label: '否，不符合', score_mapping: 0, is_default: false }
    ],
    help_text: '请根据企业实际情况选择，如有相关证明材料请一并上传'
  },
  {
    question_type: 'number',
    question_text: '产品合规性审查覆盖率（%）',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 100
    },
    placeholder: '请输入0-100之间的数值',
    help_text: '统计接受审查的新产品数量与总数的比例'
  },
  {
    question_type: 'number',
    question_text: '合规性违规事件频率（次数/年）',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 1000
    },
    placeholder: '请输入年度违规事件次数',
    help_text: '记录每年因违规而被投诉或处罚的产品相关事件次数'
  },
  {
    question_type: 'single_choice',
    question_text: '生产是否符合国家强制性标准最新版本要求？',
    input_type: 'radio',
    options: [
      { value: 'full', label: '完全符合', score_mapping: 10, is_default: false },
      { value: 'mostly', label: '基本符合（不符率<5%）', score_mapping: 7, is_default: false },
      { value: 'partial', label: '部分符合（不符率5%-20%）', score_mapping: 4, is_default: false },
      { value: 'low', label: '较少符合（不符率>20%）', score_mapping: 1, is_default: false }
    ],
    help_text: '检查与国家标准要求的不符率'
  },
  {
    question_type: 'number',
    question_text: '国家标准适配性问题整改周期（天数）',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 365
    },
    placeholder: '请输入平均整改天数',
    help_text: '记录从问题发现到解决的平均时间'
  },
  {
    question_type: 'single_choice',
    question_text: '设计电子信息产品时，是否符合《电子信息产品污染控制管理办法》要求？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，采用了污染控制方案、材料和工艺', score_mapping: 10, is_default: false },
      { value: 'partial', label: '部分采用', score_mapping: 5, is_default: false },
      { value: 'no', label: '否', score_mapping: 0, is_default: false },
      { value: 'na', label: '不适用（非电子信息产品）', score_mapping: 10, is_default: false }
    ],
    help_text: '仅适用于电子信息产品生产企业'
  },

  // ========== 企业关联方风险 ==========
  {
    question_type: 'single_choice',
    question_text: '企业是否建立了关联方交易管理制度？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，已建立完善的制度', score_mapping: 10, is_default: false },
      { value: 'partial', label: '是，但制度不够完善', score_mapping: 5, is_default: false },
      { value: 'no', label: '否，未建立', score_mapping: 0, is_default: false }
    ],
    help_text: '包括关联方识别、交易审批、信息披露等制度'
  },
  {
    question_type: 'number',
    question_text: '关联方交易金额占总交易额比例（%）',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 100
    },
    placeholder: '请输入百分比',
    help_text: '统计关联方交易金额与总交易额的比例'
  },
  {
    question_type: 'text',
    question_text: '请描述企业主要关联方及其业务往来情况',
    input_type: 'textarea',
    validation_rules: {
      min_length: 10,
      max_length: 1000
    },
    placeholder: '请详细描述关联方名称、关系类型、主要业务往来等',
    help_text: '有助于评估关联方风险集中度'
  },

  // ========== 劳务合规风险 ==========
  {
    question_type: 'single_choice',
    question_text: '企业是否与所有员工签订书面劳动合同？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，100%签订', score_mapping: 10, is_default: false },
      { value: 'mostly', label: '大部分签订（>90%）', score_mapping: 7, is_default: false },
      { value: 'partial', label: '部分签订（50%-90%）', score_mapping: 4, is_default: false },
      { value: 'low', label: '少部分签订（<50%）', score_mapping: 1, is_default: false }
    ],
    help_text: '根据《劳动合同法》，用人单位应当与劳动者签订书面劳动合同'
  },
  {
    question_type: 'single_choice',
    question_text: '企业是否按时足额缴纳社会保险？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，按时足额缴纳', score_mapping: 10, is_default: false },
      { value: 'delay', label: '偶有延迟但已补缴', score_mapping: 6, is_default: false },
      { value: 'partial', label: '存在欠缴情况', score_mapping: 3, is_default: false },
      { value: 'no', label: '未缴纳', score_mapping: 0, is_default: false }
    ],
    help_text: '包括养老、医疗、工伤、失业、生育保险'
  },
  {
    question_type: 'number',
    question_text: '过去一年劳动争议案件数量',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 1000
    },
    placeholder: '请输入案件数量',
    help_text: '包括仲裁和诉讼案件'
  },

  // ========== 企业信用风险 ==========
  {
    question_type: 'single_choice',
    question_text: '企业信用评级等级',
    input_type: 'select',
    options: [
      { value: 'AAA', label: 'AAA级', score_mapping: 10, is_default: false },
      { value: 'AA', label: 'AA级', score_mapping: 8, is_default: false },
      { value: 'A', label: 'A级', score_mapping: 6, is_default: false },
      { value: 'BBB', label: 'BBB级', score_mapping: 4, is_default: false },
      { value: 'BB', label: 'BB级及以下', score_mapping: 2, is_default: false },
      { value: 'none', label: '未评级', score_mapping: 0, is_default: false }
    ],
    help_text: '由权威信用评级机构出具的企业信用评级'
  },
  {
    question_type: 'single_choice',
    question_text: '企业是否被列入经营异常名录或严重违法失信企业名单？',
    input_type: 'radio',
    options: [
      { value: 'no', label: '否，未被列入', score_mapping: 10, is_default: false },
      { value: 'abnormal', label: '是，列入经营异常名录', score_mapping: 3, is_default: false },
      { value: 'blacklist', label: '是，列入严重违法失信名单', score_mapping: 0, is_default: false }
    ],
    help_text: '可通过国家企业信用信息公示系统查询'
  },

  // ========== 供应链风险 ==========
  {
    question_type: 'number',
    question_text: '核心供应商数量',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 1000
    },
    placeholder: '请输入供应商数量',
    help_text: '核心供应商指供应额占采购总额5%以上的供应商'
  },
  {
    question_type: 'single_choice',
    question_text: '供应商集中度风险',
    input_type: 'radio',
    options: [
      { value: 'low', label: '低（前五大供应商占比<30%）', score_mapping: 10, is_default: false },
      { value: 'medium', label: '中（前五大供应商占比30%-60%）', score_mapping: 6, is_default: false },
      { value: 'high', label: '高（前五大供应商占比>60%）', score_mapping: 2, is_default: false }
    ],
    help_text: '供应商集中度越高，供应链风险越大'
  },
  {
    question_type: 'single_choice',
    question_text: '是否建立供应商备选机制？',
    input_type: 'radio',
    options: [
      { value: 'yes', label: '是，每个核心物料都有备选供应商', score_mapping: 10, is_default: false },
      { value: 'partial', label: '部分物料有备选供应商', score_mapping: 5, is_default: false },
      { value: 'no', label: '否，未建立', score_mapping: 0, is_default: false }
    ],
    help_text: '备选供应商可降低供应中断风险'
  },

  // ========== 企业国际合作风险 ==========
  {
    question_type: 'single_choice',
    question_text: '企业是否涉及出口管制相关业务？',
    input_type: 'radio',
    options: [
      { value: 'no', label: '否，不涉及', score_mapping: 10, is_default: false },
      { value: 'yes_compliant', label: '是，已取得相关许可证', score_mapping: 8, is_default: false },
      { value: 'yes_pending', label: '是，正在申请许可证', score_mapping: 4, is_default: false },
      { value: 'yes_none', label: '是，但未申请许可证', score_mapping: 0, is_default: false }
    ],
    help_text: '涉及两用物项、军品等出口管制物项'
  },
  {
    question_type: 'number',
    question_text: '国际业务收入占比（%）',
    input_type: 'number',
    validation_rules: {
      min_value: 0,
      max_value: 100
    },
    placeholder: '请输入百分比',
    help_text: '国际业务收入占企业总收入的比例'
  },
  {
    question_type: 'multiple_choice',
    question_text: '企业主要出口目的地国家/地区（多选）',
    input_type: 'checkbox',
    options: [
      { value: 'us', label: '美国', score_mapping: 3, is_default: false },
      { value: 'eu', label: '欧盟', score_mapping: 4, is_default: false },
      { value: 'jp', label: '日本', score_mapping: 5, is_default: false },
      { value: 'sea', label: '东南亚', score_mapping: 7, is_default: false },
      { value: 'other', label: '其他地区', score_mapping: 6, is_default: false },
      { value: 'none', label: '无出口业务', score_mapping: 10, is_default: false }
    ],
    help_text: '不同国家/地区的合规要求和风险程度不同'
  }
]

