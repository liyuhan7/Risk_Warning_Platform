# 基于官方 ES 8.11.0 镜像，内置同版本 IK 中文分词插件
# 使用方式：docker compose -f build/docker-compose-elasticsearch.yml build elasticsearch
# 构建输入固定为仓库内 ik-8.11.0.zip，避免构建期下载内容漂移。
# 文件 SHA-256: ae32133c324a72379b9fafa5006c77f4c07a6269d22df8cc6528a29d09054b00
FROM docker.elastic.co/elasticsearch/elasticsearch:8.11.0

COPY ik-8.11.0.zip /tmp/ik-8.11.0.zip

# ES 镜像默认以 elasticsearch 用户运行；先校验锁定包，再以 --batch 跳过权限交互提示。
# 注：构建期非 root，COPY 的 zip 属 root 不可删除，故不在 USER 上下文内清理。
RUN echo "ae32133c324a72379b9fafa5006c77f4c07a6269d22df8cc6528a29d09054b00  /tmp/ik-8.11.0.zip" \
    | sha256sum --check --strict \
    && bin/elasticsearch-plugin install --batch file:///tmp/ik-8.11.0.zip