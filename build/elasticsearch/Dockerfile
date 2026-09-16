# 基于官方 ES 8.11.0 镜像，内置 IK 中文分词插件
# 使用方式：docker compose -f build/docker-compose-elasticsearch.yml build elasticsearch
# 前置准备：ik-8.11.0.zip 被仓库 .gitignore（*.zip）忽略，全新克隆后需先手动下载放入本目录：
#   curl -L -o ik-8.11.0.zip https://get.infini.cloud/elasticsearch/analysis-ik/8.11.0
#   （官方插件分发通道，版本号需与 ES 版本一致；亦可从 https://release.infinilabs.com 获取同版本包）
FROM docker.elastic.co/elasticsearch/elasticsearch:8.11.0

COPY ik-8.11.0.zip /tmp/ik-8.11.0.zip

# ES 镜像默认以 elasticsearch 用户运行；--batch 跳过权限交互提示
# 注：构建期非 root，COPY 的 zip 属 root 不可删除，故不在 USER 上下文内清理
RUN bin/elasticsearch-plugin install --batch file:///tmp/ik-8.11.0.zip