"""P0-06 固定 Prompt 结构化 JSON 最小验证。

对应 PLAN.md 验收标准 5：固定 Prompt 至少连续验证 10 次，每次保留原始响应、校验结果
和重试结果，失败不被静默吞掉。测试方式（PLAN.md:178）要求覆盖正常、缺字段、错枚举、
额外文本、超时和无效 JSON 六类场景。

真实调用只能产出"正常"与偶发偏差，后四类须构造注入，因此本脚本分两种模式：

    offline  用构造样本跑校验器，覆盖六类场景，不联网。确定性，可进 CI。
    live     用固定 Prompt 连续真实调用 N 次，逐次落盘原始响应与校验结果。

端点、模型与凭据一律从环境变量 LLM_BASE_URL / LLM_MODEL / LLM_API_KEY 读取，与 Java 侧
llm.* 配置同一口径，因此更换供应商无需改动脚本。脚本不含任何默认密钥，也不打印密钥内容。

用法：
    py -3.9 prompt_validation_run.py --mode offline
    $env:LLM_BASE_URL="..."; $env:LLM_MODEL="..."; $env:LLM_API_KEY="..."
    py -3.9 prompt_validation_run.py --mode live --runs 10
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from analysis_result_validator import (  # noqa: E402
    FAIL_TIMEOUT,
    validate,
)

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
PROMPT_DIR = os.path.join(HERE, "prompts")
PROMPT_PATH = os.path.join(PROMPT_DIR, "compliance-reasoning-v1.0.txt")
INPUT_PATH = os.path.join(PROMPT_DIR, "compliance-reasoning-input.json")
OUTPUT_DIR = os.path.join(REPO_ROOT, "documents", "plan0", "baseline", "p0-06-samples")

# 端点与模型经环境变量注入，与 Java 侧 llm.base-url / llm.model 保持同一套配置口径，
# 使更换供应商时脚本无需改动。默认值留空以强制显式配置。
API_URL = os.environ.get("LLM_BASE_URL", "")
MODEL = os.environ.get("LLM_MODEL", "")
TIMEOUT_SECONDS = 60
MAX_RETRIES = 2


def load_prompt(fixture):
    """把固定输入填入 Prompt 模板。占位符未被替换时直接失败，避免把 {{...}} 发给模型。"""
    with open(PROMPT_PATH, "r", encoding="utf-8") as fh:
        template = fh.read()

    evidence_texts = "\n".join(
        "[%s] %s 第 %s 页：%s"
        % (e["id"], e["sourceFileName"], e["pageNumber"], e["text"])
        for e in fixture["evidenceTexts"]
    )
    regulations = "\n".join(
        "[%s] %s\n  适用主体：%s\n  方向：%s\n  条文：%s"
        % (r["id"], r["name"], r["applicableSubject"], r["direction"], r["fullText"])
        for r in fixture["candidateRegulations"]
    )

    mapping = {
        "enterpriseName": fixture["enterpriseName"],
        "complianceDomain": fixture["complianceDomain"],
        "region": fixture["region"],
        "subject": fixture["subject"],
        "action": fixture["action"],
        "object": fixture["object"],
        "status": fixture["status"],
        "behaviorDate": fixture["behaviorDate"],
        "quantitativeData": fixture["quantitativeData"],
        "quantitativeUnit": fixture["quantitativeUnit"],
        "description": fixture["description"],
        "evidenceIds": ", ".join(fixture["evidenceIds"]),
        "evidenceTexts": evidence_texts,
        "indicatorId": fixture["indicatorId"],
        "indicatorName": fixture["indicatorName"],
        "dimension": fixture["dimension"],
        "candidateRegulations": regulations,
    }

    rendered = template
    for key, value in mapping.items():
        rendered = rendered.replace("{{%s}}" % key, str(value))

    if "{{" in rendered:
        raise ValueError("Prompt 存在未替换占位符，检查固定输入字段是否齐全")
    return rendered


def build_context(fixture):
    return {
        "allowedEvidenceIds": [e["id"] for e in fixture["evidenceTexts"]],
        "allowedRegulationIds": [r["id"] for r in fixture["candidateRegulations"]],
        "expectedIndicatorId": fixture["indicatorId"],
    }


def call_llm(prompt, api_key):
    """单次调用。返回 (原始响应文本, 耗时秒, 错误码)。超时与 HTTP 错误不抛出，作为结果返回。"""
    from urllib.error import HTTPError, URLError
    from urllib.request import Request, urlopen

    payload = json.dumps(
        {
            "model": MODEL,
            "messages": [{"role": "user", "content": prompt}],
            # 固定 Prompt 验证要求可复现，温度取 0
            "temperature": 0.0,
        }
    ).encode("utf-8")

    request = Request(
        API_URL,
        data=payload,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": "Bearer %s" % api_key,
        },
    )

    started = time.time()
    try:
        with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read().decode("utf-8")
    except URLError as exc:
        elapsed = time.time() - started
        reason = getattr(exc, "reason", exc)
        is_timeout = "timed out" in str(reason).lower()
        return None, elapsed, (FAIL_TIMEOUT if is_timeout else "NETWORK_ERROR: %s" % reason)
    except HTTPError as exc:
        elapsed = time.time() - started
        return None, elapsed, "HTTP_%s" % exc.code
    except Exception as exc:  # 未预期异常也记录，不静默
        return None, time.time() - started, "UNEXPECTED: %s" % exc

    elapsed = time.time() - started

    try:
        parsed = json.loads(body)
    except ValueError:
        return None, elapsed, "PROVIDER_RESPONSE_NOT_JSON"

    choices = parsed.get("choices") or []
    if not choices:
        return None, elapsed, "PROVIDER_NO_CHOICES"
    content = (choices[0].get("message") or {}).get("content")
    if content is None:
        return None, elapsed, "PROVIDER_NO_CONTENT"
    return content, elapsed, None


def run_live(runs, fixture):
    api_key = os.environ.get("LLM_API_KEY")
    missing = [
        name
        for name, value in (
            ("LLM_BASE_URL", API_URL),
            ("LLM_MODEL", MODEL),
            ("LLM_API_KEY", api_key),
        )
        if not value
    ]
    if missing:
        print("缺少环境变量 %s，无法进行真实调用。" % "、".join(missing))
        print("设置方式（PowerShell）：")
        print('  $env:LLM_BASE_URL="https://<供应商域名>/v1/chat/completions"')
        print('  $env:LLM_MODEL="<模型标识>"')
        print('  $env:LLM_API_KEY="<你的新Key>"')
        return None

    prompt = load_prompt(fixture)
    context = build_context(fixture)
    records = []

    print("固定 Prompt 连续验证 %d 次，模型 %s，temperature=0.0" % (runs, MODEL))
    print("Prompt 版本 %s，用例 %s\n" % (fixture["promptVersion"], fixture["caseId"]))

    for index in range(1, runs + 1):
        attempts = []
        final = None

        for attempt in range(1, MAX_RETRIES + 2):
            content, elapsed, error = call_llm(prompt, api_key)
            entry = {
                "attempt": attempt,
                "elapsedSeconds": round(elapsed, 3),
                "transportError": error,
                "rawResponse": content,
            }
            if error is None:
                result = validate(content, context)
                entry["validation"] = result.to_dict()
                entry["parsed"] = result.parsed
                attempts.append(entry)
                if result.passed:
                    final = entry
                    break
            else:
                entry["validation"] = None
                attempts.append(entry)

            if attempt <= MAX_RETRIES:
                time.sleep(2)

        if final is None:
            final = attempts[-1]

        passed = bool(final.get("validation") and final["validation"]["passed"])
        records.append(
            {
                "runIndex": index,
                "attempts": attempts,
                "finalPassed": passed,
                "retriesUsed": len(attempts) - 1,
            }
        )

        status = "通过" if passed else "失败"
        detail = ""
        if not passed:
            if final.get("transportError"):
                detail = " [%s]" % final["transportError"]
            elif final.get("validation"):
                codes = [e["code"] for e in final["validation"]["errors"]]
                detail = " %s" % codes
        print("  第 %2d 次：%s，重试 %d 次%s" % (index, status, len(attempts) - 1, detail))

    return records


# 构造失败样本。真实调用难以稳定复现，但校验器必须拒收这些形态。
# 每条对应 PLAN.md:178 列举的一类场景。
def build_offline_samples(fixture):
    valid = {
        "applicable": "APPLICABLE",
        "requirement": "一级供应商须提供有效环境许可证明，企业每年至少开展一次合规审查。",
        "enterpriseFact": "已完成 12 家一级供应商年度审查，其中 3 家未提供有效环境许可证明。",
        "complianceStatus": "NON_COMPLIANT",
        "gapType": "DOCUMENTATION_GAP",
        "gapValue": 3.0,
        "gapUnit": "家",
        "confidence": 0.83,
        "reasoning": "法规要求一级供应商全部持有有效环境许可证明，证据显示 3 家缺失且整改未完成。",
        "indicatorId": fixture["indicatorId"],
        "regulationIds": [fixture["candidateRegulations"][0]["id"]],
        "evidenceIds": [fixture["evidenceTexts"][0]["id"]],
    }

    def variant(**changes):
        item = dict(valid)
        for key, value in changes.items():
            if value is _REMOVE:
                item.pop(key, None)
            else:
                item[key] = value
        return json.dumps(item, ensure_ascii=False)

    samples = [
        {
            "id": "S01-normal",
            "category": "正常",
            "expectPass": True,
            "response": json.dumps(valid, ensure_ascii=False),
        },
        {
            "id": "S02-missing-field",
            "category": "缺字段",
            "expectPass": False,
            "response": variant(complianceStatus=_REMOVE),
        },
        {
            "id": "S03-bad-enum",
            "category": "错枚举",
            "expectPass": False,
            "response": variant(complianceStatus="PARTIALLY_COMPLIANT"),
        },
        {
            "id": "S04-extra-text",
            "category": "额外文本",
            "expectPass": False,
            "response": "好的，分析如下：\n```json\n"
            + json.dumps(valid, ensure_ascii=False)
            + "\n```\n希望对你有帮助。",
        },
        {
            "id": "S05-invalid-json",
            "category": "无效 JSON",
            "expectPass": False,
            "response": '{"applicable": "APPLICABLE", "confidence": 0.8,',
        },
        {
            "id": "S06-timeout",
            "category": "超时",
            "expectPass": False,
            "response": None,
            "transportError": FAIL_TIMEOUT,
        },
        {
            "id": "S07-empty-string-enum",
            "category": "空串枚举",
            "expectPass": False,
            "response": variant(complianceStatus=""),
        },
        {
            "id": "S08-hallucinated-regulation",
            "category": "幻觉引用",
            "expectPass": False,
            "response": variant(regulationIds=["R0000000000000000000"]),
        },
        {
            "id": "S09-hallucinated-evidence",
            "category": "幻觉引用",
            "expectPass": False,
            "response": variant(evidenceIds=["0000000000000000000000000000dead"]),
        },
        {
            "id": "S10-cross-rule-not-applicable",
            "category": "交叉规则",
            "expectPass": False,
            "response": variant(applicable="NOT_APPLICABLE", complianceStatus="NON_COMPLIANT"),
        },
        {
            "id": "S11-missing-gap-type",
            "category": "交叉规则",
            "expectPass": False,
            "response": variant(gapType=None),
        },
        {
            "id": "S12-unitless-gap",
            "category": "交叉规则",
            "expectPass": False,
            "response": variant(gapUnit=None),
        },
        {
            "id": "S13-confidence-out-of-range",
            "category": "取值越界",
            "expectPass": False,
            "response": variant(confidence=1.4),
        },
        {
            "id": "S14-unknown-applicable-mismatch",
            "category": "交叉规则",
            "expectPass": False,
            "response": variant(applicable="UNKNOWN", complianceStatus="COMPLIANT"),
        },
    ]
    return samples


class _Remove(object):
    pass


_REMOVE = _Remove()


def run_offline(fixture):
    context = build_context(fixture)
    samples = build_offline_samples(fixture)
    records = []
    failures = 0

    print("构造样本校验，共 %d 条\n" % len(samples))
    for sample in samples:
        if sample.get("transportError"):
            # 传输层失败不进入校验器，直接记为该类错误
            result_dict = {
                "passed": False,
                "errors": [{"code": sample["transportError"], "field": None, "detail": "传输层失败"}],
                "warnings": [],
                "hadExtraText": False,
            }
            actual_pass = False
        else:
            result = validate(sample["response"], context)
            result_dict = result.to_dict()
            actual_pass = result.passed

        matched = actual_pass == sample["expectPass"]
        if not matched:
            failures += 1

        records.append(
            {
                "id": sample["id"],
                "category": sample["category"],
                "expectPass": sample["expectPass"],
                "actualPass": actual_pass,
                "matched": matched,
                "rawResponse": sample["response"],
                "validation": result_dict,
            }
        )

        flag = "OK " if matched else "!! "
        codes = [e["code"] for e in result_dict["errors"]]
        print(
            "  %s%-32s %-8s 期望%s 实际%s %s"
            % (
                flag,
                sample["id"],
                sample["category"],
                "通过" if sample["expectPass"] else "拒收",
                "通过" if actual_pass else "拒收",
                codes if codes else "",
            )
        )

    print("\n构造样本结果：%d/%d 符合预期" % (len(samples) - failures, len(samples)))
    return records, failures


def save(records, filename):
    if not os.path.isdir(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)
    path = os.path.join(OUTPUT_DIR, filename)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(records, fh, ensure_ascii=False, indent=2)
    print("已保存 %s" % os.path.relpath(path, REPO_ROOT))


def main():
    parser = argparse.ArgumentParser(description="P0-06 固定 Prompt JSON 验证")
    parser.add_argument("--mode", choices=["offline", "live"], default="offline")
    parser.add_argument("--runs", type=int, default=10, help="live 模式的调用次数")
    args = parser.parse_args()

    with open(INPUT_PATH, "r", encoding="utf-8") as fh:
        fixture = json.load(fh)

    if args.mode == "offline":
        records, failures = run_offline(fixture)
        save(records, "offline-samples.json")
        return 1 if failures else 0

    records = run_live(args.runs, fixture)
    if records is None:
        return 2

    passed = sum(1 for r in records if r["finalPassed"])
    retried = sum(1 for r in records if r["retriesUsed"] > 0)
    print("\n通过 %d/%d 次，其中 %d 次用到重试" % (passed, len(records), retried))
    save(records, "live-runs.json")
    return 0 if passed == len(records) else 1


if __name__ == "__main__":
    sys.exit(main())
