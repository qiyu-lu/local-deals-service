# Server version matches the Elasticsearch Java client managed by Spring Boot (8.18.8).
FROM elasticsearch:8.18.8
# infinilabs publishes the IK plugin zips on release.infinilabs.com (GitHub release assets 404).
RUN bin/elasticsearch-plugin install --batch \
    https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.18.8.zip
