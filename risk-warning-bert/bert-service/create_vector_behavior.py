import json
import requests
import time

def add_vectors_to_json(input_file, output_file, batch_size=10):
    """
    批量将 JSON 文件中的 description 字段转化为语义向量
    
    Args:
        input_file: 输入 JSON 文件路径
        output_file: 输出 JSON 文件路径
        batch_size: 批量处理大小，避免请求过大
    """
    # 读取原始 JSON 文件
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    # 分批处理，避免一次性请求过大
    for i in range(0, len(data), batch_size):
        batch = data[i:i+batch_size]
        
        # 提取当前批次的描述字段
        descriptions = []
        desc_indices = []  # 记录描述字段在原数据中的索引
        
        for j, item in enumerate(batch):
            if item.get('description'):
                descriptions.append(item['description'])
                desc_indices.append(j + i)  # 计算在原数据中的索引
        
        if descriptions:
            # 调用 BERT 服务进行向量化
            url = "http://localhost:8000/encode"
            payload = {"texts": descriptions}
            headers = {"Content-Type": "application/json"}
            
            try:
                response = requests.post(url, json=payload, headers=headers)
                response.raise_for_status()
                vectors = response.json()
                
                # 将向量添加到对应的数据项中
                for k, vector in enumerate(vectors):
                    original_idx = desc_indices[k]
                    data[original_idx]['vector'] = vector
                
                print(f"已处理批次 {i//batch_size + 1}, 向量化 {len(descriptions)} 个描述")
                
            except requests.exceptions.RequestException as e:
                print(f"请求错误: {e}")
                continue
            
            # 避免请求过于频繁
            time.sleep(0.1)
    
    # 保存更新后的文件
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    
    print(f"已保存到 {output_file}")

# 使用示例
add_vectors_to_json('enterprise_behaviors.json', 'enterprise_behaviors_with_vectors.json')
