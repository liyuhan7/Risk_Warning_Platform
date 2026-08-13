import json
import requests
import os

ES_BASE_URL = "http://localhost:9200"

def create_indices():
    with open("documents/es_mappings.json", "r", encoding="utf-8") as f:
        mappings = json.load(f)
    
    # Replace IK analyzer with standard if IK is not installed
    mappings_str = json.dumps(mappings).replace("ik_max_word", "standard")
    mappings = json.loads(mappings_str)
    
    for index_name, mapping in mappings.items():
        print(f"Creating index: {index_name}")
        # Delete index if exists
        requests.delete(f"{ES_BASE_URL}/{index_name}")
        # Create index
        resp = requests.put(f"{ES_BASE_URL}/{index_name}", json=mapping)
        print(f"Response: {resp.status_code} - {resp.text}")

def bulk_import(index_name, file_path):
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        return
    
    with open(file_path, "r", encoding="utf-8") as f:
        items = json.load(f)
    
    bulk_data = ""
    for item in items:
        # Ensure ID exists for bulk index
        item_id = item.get("id")
        action = {"index": {"_index": index_name}}
        if item_id:
            action["index"]["_id"] = str(item_id)
            
        bulk_data += json.dumps(action, ensure_ascii=False) + "\n"
        bulk_data += json.dumps(item, ensure_ascii=False) + "\n"
    
    if bulk_data:
        print(f"Bulk importing {len(items)} items to {index_name}")
        resp = requests.post(f"{ES_BASE_URL}/_bulk", 
                             data=bulk_data.encode('utf-8'), 
                             headers={"Content-Type": "application/x-ndjson"})
        print(f"Response: {resp.status_code}")
        # Only print first few chars of error if failed
        if resp.status_code != 200:
            print(resp.text[:500])

if __name__ == "__main__":
    create_indices()
    bulk_import("t_indicator", "documents/data/indicator.json")
    bulk_import("t_regulation", "documents/data/regulation.json")
    bulk_import("t_behavior", "documents/data/behavior.json")
    print("ES Initialization and Bulk Import completed.")
