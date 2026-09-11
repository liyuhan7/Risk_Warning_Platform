# -*- coding: utf-8 -*-
"""生成基线演示材料 docx（华东智联供应链 2026 年度业务合规事实）。

内容为纯业务事实段落：无元信息（材料性质/编制说明/风险提示/评估期间）、无章节标题，
每段包含可评估的具体事实（主体-行为-对象-数值/时间），状态以 COMPLETED 为主、
少量 IN_PROGRESS。输出为最小合法 OOXML docx（Apache POI XWPF 可按段落读取）。
"""
import html
import zipfile

PARAGRAPHS = [
    "公司现有员工212人，其中正式员工187人、劳务派遣及临时用工25人。",
    "公司2026年1月至6月实现营业收入8600万元，其中供应链采购代理收入占比58%、仓储配送收入占比27%、线上营销及跨境客户服务收入占比15%。",
    "公司与78家供应商保持长期合作，其中一级供应商12家、二级供应商66家。",
    "采购部门于2026年5月完成12家一级供应商年度资质审查，10家通过审查并续签年度框架合同，2家因资质证明不完整被暂停合作并要求三个月内补齐材料。",
    "公司于2026年3月与供应商A签订金额120万元的仓储设备采购合同，采购过程履行了比价程序并留存3家报价单和集体决策记录。",
    "2026年上半年公司完成2笔单一来源采购，均履行例外审批并留存书面审批意见，两笔合计金额35万元。",
    "公司与全部78家合作供应商的采购合同已于2026年4月前完成条款更新，全部补充了反商业贿赂与审计配合条款。",
    "采购经理于2026年6月完成全部12家一级供应商的年度评价，评价结果已报管理层审批并归档。",
    "市场部门于2026年5月完成官网及宣传材料的合规复核，删除“行业领先”“零风险保障”等绝对化表述，复核记录已归档。",
    "公司在2026年6月促销活动中的有奖销售公布并披露了中奖概率、兑奖条件和有效期限，活动期间收到消费者投诉6起，全部于3个工作日内处理完毕。",
    "公司于2026年3月完成某批次智能仓储设备的召回评估，共召回设备45台，完成维修及客户回访后全部售后问题关闭。",
    "公司于2026年1月与全部25名临时用工人员签订书面劳动合同，其中23人按实际工资基数足额缴纳社会保险，2人按约定基数缴纳并留存书面说明档案。",
    "仓储中心2026年5月业务高峰期共安排12名员工月加班46小时，低于法定上限，并留存员工书面同意与调休安排记录。",
    "公司向实际控制人亲属经营的运输企业采购运输服务，2026年上半年交易金额240万元，已履行关联关系申报与价格公允性比较，比较记录留存备查。",
    "公司于2026年4月补缴2025年第四季度税款32万元，税款已结清并取得税务机关完税凭证。",
    "线上客户系统于2026年6月完成隐私政策与授权流程更新，个人信息收集改为单独勾选取得授权，并明确说明保存期限与删除渠道。",
    "公司于2026年7月完成1.8万条客户个人信息的单独授权补登记，剩余1.2万条历史信息的补登记正在进行，预计2026年9月完成。",
    "公司2026年6月将3万条境内客户联系信息同步给境外客户服务商用于工单处理，同步前完成了数据出境风险评估并通过合规评审。",
    "信息系统权限检查于2026年6月完成，检查发现并禁用5名离职员工账号，核查离职后登录记录后未发现异常数据访问。",
    "公司于2026年7月成立合规整改工作组，工作组每周召开例会，跟进各领域合规整改事项。",
    "供应商尽调档案补全正在进行，已完成11家一级供应商的尽调档案，剩余1家预计于2026年8月完成。",
    "数据出境风险评估正在进行，已完成评估范围的70%，预计2026年11月完成全部评估。",
    "公司2026年上半年新增2笔关联交易，金额合计85万元，均履行审批程序并向管理层披露。",
    "公司2025年度经营审计于2026年4月出具无保留意见审计报告。",
    "客户投诉台账显示2026年上半年新增投诉23起，全部在3个工作日内响应，处理关闭率100%。",
    "公司2026年上半年完成全员合规培训2次，培训覆盖全部187名正式员工，培训记录已归档。",
]


def esc(text):
    return html.escape(text, quote=False)


def build_docx(path):
    paras_xml = "".join(
        "<w:p><w:r><w:t xml:space=\"preserve\">" + esc(p) + "</w:t></w:r></w:p>"
        for p in PARAGRAPHS
    )
    doc_xml = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        "<w:body>" + paras_xml + "<w:sectPr/></w:body></w:document>"
    )
    content_types = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        "<Override PartName=\"/word/document.xml\" "
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        "</Types>"
    )
    rels = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        "<Relationship Id=\"rId1\" "
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
        "Target=\"word/document.xml\"/>"
        "</Relationships>"
    )
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", rels)
        archive.writestr("word/document.xml", doc_xml)


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else \
        r"documents\华东智联供应链有限公司_2026年度业务合规事实材料_基线演示样例.docx"
    build_docx(out)
    with zipfile.ZipFile(out) as archive:
        xml = archive.read("word/document.xml").decode("utf-8")
    print("written:", out)
    print("paragraph count:", xml.count("<w:p>"), "(expect %d)" % len(PARAGRAPHS))