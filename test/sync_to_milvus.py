import requests
import json
import time

# 配置参数
BASE_URL = "http://localhost:8088/api/knowledge/test/vectorization/batch-store"
ES_URL_TEMPLATE = "http://localhost:9200/{index}/_search?size={size}&from={from_}"
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJmdWxsTmFtZSI6Iua1i-ivleeUqOaItyIsInVzZXJJZCI6MiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwic3ViIjoidGVzdEBleGFtcGxlLmNvbSIsImlhdCI6MTc3NTQ4NDkzOCwiZXhwIjoxNzc1NTcxMzM4fQ.pmfEJeztFxDCI0HJa-bCJKyt4TPd9fJDkA_qa74C6Vo"
BATCH_SIZE = 50

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json"
}

def sync_index(es_index, milvus_collection, text_field):
    print(f"\n>>> 开始同步索引: {es_index} -> {milvus_collection}")
    
    # 1. 获取总数
    res = requests.get(f"http://localhost:9200/{es_index}/_count")
    total = res.json()['count']
    print(f"总数据量: {total}")

    for start in range(0, total, BATCH_SIZE):
        # 2. 从 ES 分批获取
        es_res = requests.get(ES_URL_TEMPLATE.format(index=es_index, size=BATCH_SIZE, from_=start))
        hits = es_res.json()['hits']['hits']
        
        # 3. 封装 DTO 数据列表
        data_list = []
        for hit in hits:
            source = hit['_source']
            industries = source.get('industry', [])
            industry_str = ",".join(industries) if isinstance(industries, list) else str(industries)
            
            # 特殊处理文本字段：indicator 用 name, regulation 用 full_text
            text_val = source.get(text_field, "")
            if not text_val:
                continue

            data_list.append({
                "id": hit['_id'],
                "text": text_val,
                "esId": hit['_id'],
                "name": source.get('name', ''),
                "dimension": source.get('dimension', ''),
                "industry": industry_str,
                "region": source.get('region', '')
            })
        
        # 4. POST 到微服务进行向量化存储
        payload = {
            "collectionName": milvus_collection,
            "data": data_list
        }
        
        try:
            store_res = requests.post(BASE_URL, headers=HEADERS, json=payload)
            if store_res.status_code == 200:
                print(f"进度: {start + len(data_list)}/{total} 成功", end='\r')
            else:
                print(f"\n批次 {start} 失败: {store_res.text}")
        except Exception as e:
            print(f"\n批次 {start} 异常: {str(e)}")
        
        # 稍微避让 BERT 服务，防止其 CPU 过载
        time.sleep(0.5)
    print(f"\n<<< 索引 {es_index} 同步结束")

if __name__ == "__main__":
    # 执行同步
    # 1. 监管法规
    sync_index("t_regulation", "regulation_vectors", "full_text")
    # 2. 预警指标
    sync_index("t_indicator", "indicator_vectors", "name")
    print("\n[FINISH] 本次两库同步已全部启动并完成托管处理。")
