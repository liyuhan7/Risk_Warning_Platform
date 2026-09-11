/**
 * 问卷选项结构
 * 对应 questionnaire_form.options 的嵌套结构
 */
export interface QuestionOption {
  value: string;
  label: string;
  score_mapping: number;
  is_default: boolean;
}

/**
 * 问卷验证规则
 * 对应 questionnaire_form.validation_rules 的结构
 */
export interface ValidationRules {
  min_length?: number;
  max_length?: number;
  min_value?: number;
  max_value?: number;
  pattern?: string;
  custom_validation?: string;
}

/**
 * 问卷表单结构
 * 对应 questionnaire_form 的完整结构
 */
export interface QuestionnaireForm {
  question_type: string;
  question_text: string;
  input_type: string;
  validation_rules?: ValidationRules;
  options?: QuestionOption[];
  help_text?: string;
  placeholder?: string;
}

/**
 * 对应 es_mappings.json 中的 t_indicator 索引结构
 * 用于存储指标体系定义，包含指标的基本信息、计算规则、风险规则以及问卷表单配置
 */
export interface EsIndicator {
  id: string;
  name: string;
  description: string;
  type: string;
  indicator_level: number;
  parent_indicator_id: string | null;
  dimension: string;
  industry: string[];
  region: string;
  tags: string[];
  max_score: number;
  calculation_rule: {
    rule_type: string;
    binary_rule?: {
      condition: string;
      true_score: number;
      false_score: number;
    };
    range_rule?: {
      min_value: number;
      max_value: number;
      calculation_method: string;
      out_of_range_score: number;
      max_score: number;
    };
  };
  risk_rule: {
    description: string;
    static_threshold?: {
      operator: string;
      threshold_value: number;
      risk_level: string;
    };
    adjustment_factor?: {
      enabled: boolean;
      required_low_count: number;
      risk_level_increase: string;
    };
  };
  questionnaire_form?: QuestionnaireForm;
  vector?: number[];
  created_at: string;
}

/**
 * 对应 es_mappings.json 中的 t_risk 索引结构
 * 用于存储风险评估结果，包含风险等级、概率、影响程度以及触发规则和相关法规
 */
export interface EsRisk {
  id: string;
  project_id: number;
  assessment_id: number;
  name: string;
  dimension: string;
  description: string;
  risk_level: string;
  probability: number;
  impact: number;
  detectability: number;
  status: string;
  responsible_party: string;
  affected_objects: string;
  impact_scope: string;
  countermeasures: string;
  triggered_by_rule: {
    rule_id: string;
    rule_description: string;
    trigger_condition: string;
  };
  related_indicators: Array<{
    indicator_id: string;
    indicator_name: string;
    score: number;
    threshold: number;
    is_primary_trigger: boolean;
  }>;
  related_regulations: Array<{
    regulation_id: string;
    regulation_name: string;
    violation_type: string;
    compliance_requirement: string;
  }>;
  created_at: string;
}

/**
 * 对应 es_mappings.json 中的 t_behavior 索引结构
 * 用于存储企业行为数据，记录企业的具体行为、状态及量化数据
 */
export interface EsBehavior {
  id: string;
  project_id: number;
  description: string;
  type: string;
  dimension: string;
  tags: string[];
  status: string;
  quantitative_data: number;
  behavior_date: string;
  vector?: number[];
  created_at: string;
}

/**
 * 对应 es_mappings.json 中的 t_regulation 索引结构
 * 用于存储法规库数据，包含法规全文、适用对象及量化指标
 */
export interface EsRegulation {
  id: string;
  name: string;
  type: string;
  dimension: string;
  industry: string;
  tags: string[];
  region: string;
  applicable_subject: string;
  full_text: string;
  direction: string;
  quantitative_indicator: number;
  vector?: number[];
  created_at: string;
}

/**
 * 风险维度枚举
 * 对应后端的 RiskDimension 枚举，用于分类展示风险指标
 */
export enum RiskDimension {
  ENTERPRISE_RELATED_RISK = '企业关联方风险',
  PRODUCT_LEGITIMACY_RISK = '产品合规风险',
  LABOR_LEGITIMACY_RISK = '劳务合规风险',
  ENTERPRISE_CREDIT_RISK = '企业信用风险',
  ENTERPRISE_INTERNATIONAL_COOPERATION_RISK = '企业国际合作风险',
  SUPPLY_CHAIN_RISK = '供应链风险'
}

/**
 * 风险维度列表，用于侧边栏菜单展示
 */
export const RiskDimensionList: string[] = Object.values(RiskDimension)
