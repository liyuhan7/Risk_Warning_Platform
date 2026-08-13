# -*- coding: utf-8 -*-
import requests
import json

KNOWLEDGE_SERVICE_URL = "http://localhost:8088/api/knowledge/test/vectorization/search/regulations"
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJmdWxsTmFtZSI6Iua1i-ivleeUqOaItyIsInVzZXJJZCI6MiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwic3ViIjoidGVzdEBleGFtcGxlLmNvbSIsImlhdCI6MTc3NTQ4NDkzOCwiZXhwIjoxNzc1NTcxMzM4fQ.pmfEJeztFxDCI0HJa-bCJKyt4TPd9fJDkA_qa74C6Vo"

def search_milvus(query_text):
    print(f"[*] 通过知识微服务 (Milvus数据库) 查询: '{query_text}' ...\n")
    
    payload = {
        "queryText": query_text,
        "topK": 3
    }
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {TOKEN}"
    }
    
    try:
        response = requests.post(KNOWLEDGE_SERVICE_URL, headers=headers, json=payload, timeout=10)
        response.raise_for_status()
        data = response.json()
        
        if data.get("success"):
            results = data.get("results", [])
            print(f"[+] 检索成功！共找到 {data.get('count')} 条相关结果:\n")
            
            for idx, res in enumerate(results, 1):
                print(f"--- 匹配结果 {idx} ---")
                print(f"相似度评分 : {res.get('score'):.4f}")
                print(f"法规名称   : {res.get('name')}")
                print(f"所属维度   : {res.get('dimension')}")
                print(f"所属行业   : {res.get('industry')}")
                print()
        else:
            print("[!] 查询失败:", data.get("error"))
            
    except requests.exceptions.RequestException as e:
        print("[!] 服务调用失败:", e)
    except Exception as e:
        print("[!] 发生未知错误:", e)

if __name__ == "__main__":
    complex_query = "关于金融机构或商业银行在反洗钱、资金流水监管方面的处罚规定"
    search_milvus(complex_query)
