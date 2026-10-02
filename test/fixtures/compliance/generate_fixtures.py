# -*- coding: utf-8 -*-
"""P0 固定测试案例生成脚本（指标驱动版）。

金标准原则：
- 测试材料严格依据 documents/data/indicator.json 中真实存在的可执行三级指标构造；
- 每个事实段落对应一个指标的 BINARY 判定条件，句子只负责让条件真假唯一可判；
- expected.json 中的预期得分 / 风险触发由脚本从指标规则机械推导，不依赖人工常识判断；
- CASE-001 与 CASE-002 使用同一批指标做镜像对照（同一规则、相反事实、相反结论），
  CASE-003 仅提及指标主题但不含任何可判定事实。

段落约束（对齐 processing 模块 ContentExtractor 分段规则）：10~500 字符且含中文。
"""
import hashlib
import json
import os
import re

from docx import Document

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(BASE_DIR, "..", "..", ".."))
INDICATOR_FILE = os.path.join(REPO_ROOT, "documents", "data", "indicator.json")

# 已人工审批冻结的运行时标识。重跑本脚本不得改变这些值，
# 否则回归比对将失去与历史 Baseline 的可对照性。
FROZEN_PROJECT_IDS = {"CASE-001": 3, "CASE-002": 4, "CASE-003": 5}

# 清库重跑后由采集脚本回填，键缺失表示尚未产生有效评估。
FROZEN_ASSESSMENT_IDS = {"CASE-001": 32, "CASE-002": 33, "CASE-003": 34}


def sha256_of(path):
    """返回文件内容的 SHA-256，用于冻结输入完整性校验。"""
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()

# 选用的指标 id 前 8 位 -> 段落文本
# expectedConditionAnswer 含义：句子描述的事实使 BINARY 条件成立为 True / 不成立为 False
CASES = {
    "CASE-001": {
        "caseType": "NON_COMPLIANT",
        "companyName": "宏图电子制造有限公司",
        "background": "宏图电子制造有限公司成立于2017年，总部位于中国东莞，主要从事消费电子产品的生产与出口业务，产品销往国内及欧盟市场。",
        "facts": [
            ("25c2262a", False, "经属地生态环境部门核查，宏图电子制造有限公司生产基地尚未取得排污许可证，生产线目前处于正常运转状态。"),
            ("150e2357", False, "2025年第二季度监督抽检中，公司一批出口欧盟产品经检测发现铅含量超出欧盟RoHS2.0十项物质限值要求，该批次检测结果判定为不合格。"),
            ("edb74ec5", True, "考勤记录显示，公司装配车间多名员工2025年5月人均加班时长达到58小时，已超过法定最高加班时长。"),
            ("8210bf7d", False, "专项审计发现，公司未按照法定社会保险缴费基数申报缴费，而是按最低工资标准为部分员工缴纳社会保险。"),
            ("76783faa", False, "公司两款出口欧盟的产品无法提供完整的CE认证技术文件，经复核不符合欧盟CE认证要求。"),
            ("63c52b9d", False, "公司将包含用户信息的订单数据传输至境外服务器前未开展数据出境安全评估，不符合《数据安全法》数据出境要求。"),
            ("f8ab34b2", False, "现场检查发现公司生产过程未设置不合格品处理程序，不合格产品与合格产品混放且无处置记录。"),
            ("e43ca387", False, "市场监督抽查发现，公司一款在国内销售的产品未加贴CCC认证标志，属于CCC认证标志使用不规范情形。"),
        ],
    },
    "CASE-002": {
        "caseType": "COMPLIANT",
        "companyName": "正源智能装备有限公司",
        "background": "正源智能装备有限公司成立于2016年，总部位于中国佛山，主要从事智能装备与电子部件的生产，产品面向国内及欧盟市场。",
        "facts": [
            ("25c2262a", True, "公司生产基地已依法取得排污许可证，证书在有效期内，并按证载要求开展自行监测与执行报告。"),
            ("150e2357", True, "2025年第二季度公司出口欧盟产品全部通过欧盟RoHS2.0十项物质检测，铅、汞等各项有害物质含量均低于限值。"),
            ("edb74ec5", False, "人事考勤系统统计显示，公司全体员工月度加班时长均控制在法定最高加班时长以内，无超时加班记录。"),
            ("8210bf7d", True, "公司严格按照法定社会保险缴费基数为其全部员工缴纳社会保险，申报及缴纳入账记录完整可查。"),
            ("76783faa", True, "公司出口欧盟产品均已按要求完成CE认证并签发符合性声明，技术文件齐全，符合欧盟CE认证要求。"),
            ("63c52b9d", True, "公司向境外传输用户数据前已完成数据出境安全评估并获得通过，符合《数据安全法》数据出境要求。"),
            ("f8ab34b2", True, "公司生产过程已设置不合格品处理程序，不合格品均隔离存放并留存完整的评审与处置记录。"),
            ("e43ca387", True, "公司在中国市场销售的各型号产品均已规范加贴CCC认证标志，标志使用符合相关规定。"),
        ],
    },
    "CASE-003": {
        "caseType": "INSUFFICIENT_EVIDENCE",
        "companyName": "云枢信息科技有限公司",
        "background": "云枢信息科技有限公司成立于2023年，总部位于中国成都，主要从事物联网终端设备的研发与生产，产品当前面向国内市场并筹备出口欧盟。",
        "facts": [
            ("25c2262a", None, "公司新厂区环保相关手续的办理进度由行政部统一对接跟进，具体情况以后续正式通报为准。"),
            ("150e2357", None, "公司计划对拟出口欧盟的产品开展有害物质检测相关工作，具体实施方案仍在拟定过程中。"),
            ("8210bf7d", None, "人力资源部正在核对全员社会保险缴纳口径与基数执行情况，相关统计结果将另行说明。"),
            ("63c52b9d", None, "公司管理层近期关注到数据出境监管要求的更新动态，已安排专人收集整理相关政策信息。"),
            ("edb74ec5", None, "生产部门反馈近期存在弹性排班安排，详细的工时与考勤数据待汇总完成后统一归档备查。"),
            ("76783faa", None, "关于欧盟市场准入所需的CE认证事项，公司正在了解具体办理流程与所需材料清单。"),
        ],
    },
}


# 每个指标主题对应的法规件文检索关键词（按名称子串匹配 regulation.json）
REG_HINTS = {
    "25c2262a": ["排污许可管理条例"],
    "150e2357": ["欧盟RoHS指令-铅含量限制", "欧盟RoHS指令-有害物质限量", "欧盟 RoHS 2.0 十项物质检测是否合格"],
    "edb74ec5": ["工时制度规定", "劳动合同法-工时记录"],
    "8210bf7d": ["社会保险法-社保缴纳", "社会保险法-养老保险"],
    "76783faa": ["是否符合欧盟CE认证要求", "欧盟CE标识可追溯要求"],
    "63c52b9d": ["数据出境安全评估办法", "GDPR-数据跨境传输", "数据安全法-重要数据识别"],
    "f8ab34b2": ["产品质量法-不合格产品", "不合格物资处理"],
    "e43ca387": ["强制性产品认证管理规定(CCC)", "CCC认证管理规定"],
}


def load_indicators():
    data = json.load(open(INDICATOR_FILE, encoding="utf-8"))
    items = data if isinstance(data, list) else data.get("items", [])
    return items


def resolve(items, prefix):
    """按 id 前 8 位解析指标，必须唯一命中且具备可执行 BINARY 规则。"""
    hits = [it for it in items if str(it.get("id", "")).startswith(prefix)]
    if len(hits) != 1:
        raise ValueError("指标前缀 %s 命中 %d 条，要求唯一" % (prefix, len(hits)))
    it = hits[0]
    rule = it.get("calculationRule") or {}
    br = rule.get("binaryRule") or {}
    if it.get("indicatorLevel") != 3 or rule.get("ruleType") != "BINARY":
        raise ValueError("指标 %s 不是可执行 BINARY 三级指标" % it.get("name"))
    if not br.get("condition") or br.get("trueScore") is None or br.get("falseScore") is None:
        raise ValueError("指标 %s 的 binaryRule 字段不完整" % it.get("name"))
    return it


def resolve_regulations(prefix, regulations):
    """按名称子串解析指标对应的法规件文；法规种子数据无 id 字段，仅能以名称锚定。"""
    found, missing = [], []
    for kw in REG_HINTS.get(prefix, []):
        hits = [r for r in regulations if kw in str(r.get("name", ""))]
        if hits:
            r = hits[0]
            found.append({
                "name": r.get("name"),
                "type": r.get("type"),
                "direction": r.get("direction"),
                "quantitativeIndicator": r.get("quantitative_indicator"),
                "applicableSubject": r.get("applicable_subject"),
                "fullText": (r.get("full_text") or "")[:120],
            })
        else:
            missing.append(kw)
    return found, missing


def build_expected(case_id, spec, resolved, regulations):
    """从指标规则机械推导预期得分与风险触发，禁止人工填写数值。"""
    facts = []
    for i, (prefix, answer, _text) in enumerate(spec["facts"], 1):
        it = resolved[prefix]
        br = it["calculationRule"]["binaryRule"]
        rr = it.get("riskRule") or {}
        st = rr.get("staticThreshold") or {}
        regs, missing = resolve_regulations(prefix, regulations)
        if missing:
            raise ValueError("指标 %s 的法规关键词未命中: %s" % (it.get("name"), missing))
        if answer is None:
            # 证据不足：条件真假不可判定，预期得分与风险触发均为未知
            facts.append({
                "factId": "%s-F%02d" % (case_id, i),
                "paragraphIndex": i,
                "indicatorId": it["id"],
                "indicatorName": it.get("name"),
                "dimension": it.get("dimension"),
                "condition": br.get("condition"),
                "ruleType": "BINARY",
                "expectedConditionAnswer": None,
                "expectedScore": None,
                "indicatorMaxScore": it.get("maxScore"),
                "riskRule": {
                    "staticThreshold": st or None,
                    "adjustmentFactor": (rr.get("adjustmentFactor") or None) if isinstance(rr, dict) else None,
                },
                "expectedRiskTriggered": None,
                "expectedRegulations": regs,
                "evidenceText": _text,
            })
            continue
        score = float(br["trueScore"]) if answer else float(br["falseScore"])
        operator = st.get("operator")
        threshold = st.get("thresholdValue")
        if operator == "<":
            triggered = score < threshold
        elif operator == "<=":
            triggered = score <= threshold
        elif operator == ">":
            triggered = score > threshold
        else:
            triggered = None
        regs, missing = resolve_regulations(prefix, regulations)
        if missing:
            raise ValueError("指标 %s 的法规关键词未命中: %s" % (it.get("name"), missing))
        facts.append({
            "factId": "%s-F%02d" % (case_id, i),
            "paragraphIndex": i,
            "indicatorId": it["id"],
            "indicatorName": it.get("name"),
            "dimension": it.get("dimension"),
            "condition": br.get("condition"),
            "ruleType": "BINARY",
            "expectedConditionAnswer": answer,
            "expectedScore": score,
            "indicatorMaxScore": it.get("maxScore"),
            "riskRule": {
                "staticThreshold": st or None,
                "adjustmentFactor": (rr.get("adjustmentFactor") or None) if isinstance(rr, dict) else None,
            },
            "expectedRiskTriggered": triggered,
            "expectedRegulations": regs,
            "evidenceText": _text,
        })
    return {
        "caseId": case_id,
        "caseType": spec["caseType"],
        "companyName": spec["companyName"],
        "generationBasis": "documents/data/indicator.json（金标准得分与风险触发由脚本从指标规则机械推导）",
        "projectId": FROZEN_PROJECT_IDS.get(case_id),
        "assessmentId": FROZEN_ASSESSMENT_IDS.get(case_id),
        "sourceDocuments": [{
            "fileName": "source.docx",
            "fileHashAlgorithm": "SHA-256",
            "fileHash": sha256_of(os.path.join(BASE_DIR, case_id, "source.docx")),
        }],
        "facts": facts,
        "expectedComplianceStatus": spec["caseType"],
        "notes": "projectId 与 fileHash 已人工审批冻结；fileHash 为 source.docx 内容的 SHA-256，"
                 "比对时须先校验输入文件一致。assessmentId 待清库重跑后回填。"
                 "背景段不计入判定事实；"
                 "CASE-003 的 expectedConditionAnswer 为 null 表示材料故意不含可判定证据。",
    }


def check_sentence(text):
    if not (10 <= len(text) <= 500):
        raise ValueError("段落长度越界: %d" % len(text))
    if not re.search(r"[\u4e00-\u9fff]", text):
        raise ValueError("段落不含中文")


def main():
    items = load_indicators()
    reg_data = json.load(open(os.path.join(REPO_ROOT, "documents", "data", "regulation.json"), encoding="utf-8"))
    regulations = reg_data if isinstance(reg_data, list) else reg_data.get("items", [])
    summary = []
    for case_id, spec in CASES.items():
        resolved = {prefix: resolve(items, prefix) for prefix, _, _ in spec["facts"]}
        doc = Document()
        doc.add_paragraph(spec["background"])
        for _, _, text in spec["facts"]:
            check_sentence(text)
            doc.add_paragraph(text)
        case_dir = os.path.join(BASE_DIR, case_id)
        os.makedirs(case_dir, exist_ok=True)
        docx_path = os.path.join(case_dir, "source.docx")
        # docx 已冻结：python-docx 每次保存都会写入新的时间戳元数据，
        # 重新生成会使 SHA-256 漂移并让历史 Baseline 失去可对照性。
        # 需要改材料时先显式删除 source.docx，并同步作废已冻结的 fileHash。
        if not os.path.exists(docx_path):
            doc.save(docx_path)
        expected = build_expected(case_id, spec, resolved, regulations)
        out = os.path.join(case_dir, "expected.json")
        json.dump(expected, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        for f in expected["facts"]:
            score_text = "未知" if f["expectedScore"] is None else "%.1f" % f["expectedScore"]
            summary.append("%s | %-14s | 答=%s | 预期得分=%s | 触发风险=%s | %s" % (
                case_id, f["indicatorName"][:14], f["expectedConditionAnswer"],
                score_text, f["expectedRiskTriggered"], f["dimension"]))
    print("\n".join(summary))


if __name__ == "__main__":
    main()
