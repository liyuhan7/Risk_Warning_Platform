# -*- coding: utf-8 -*-
import requests
import json

CLASSIFY_URL = "http://localhost:8000/classify"

def test_classify(text, input_type):
    print(f"[*] 测试分类接口...")
    print(f"    输入文本: '{text}'")
    print(f"    场景类型 (input_type): '{input_type}'")
    
    payload = {
        "text": text,
        "input_type": input_type
    }
    
    headers = {
        "Content-Type": "application/json"
    }
    
    try:
        response = requests.post(CLASSIFY_URL, headers=headers, json=payload, timeout=10)
        response.raise_for_status()
        data = response.json()
        
        print("\n[+] 分类预测结果:")
        print(json.dumps(data, indent=4, ensure_ascii=False))
            
    except requests.exceptions.RequestException as e:
        print("[!] 服务调用失败:", e)
        if hasattr(e, 'response') and e.response is not None:
            print(e.response.text)
    except Exception as e:
        print("[!] 发生未知错误:", e)

if __name__ == "__main__":
    # 测试监管法规分类
    regulation_text = "商业银行应当建立健全反洗钱内部控制制度，设立反洗钱专门机构。"
    test_classify(regulation_text, "regulation")
    
    print("-" * 50)
    
    # 测试用户行为分类
    behavior_text = "某分行高管利用职务之便，挪用巨额资金进行高风险股票投资。"
    test_classify(behavior_text, "behavior")
