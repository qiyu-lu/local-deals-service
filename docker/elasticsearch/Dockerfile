# docker/elasticsearch/Dockerfile
FROM elasticsearch:7.17.18
# NOTE: infinilabs/analysis-ik no longer publishes versioned zips under GitHub Releases
# (github.com/infinilabs/analysis-ik/releases/download/... 404s). The project now
# distributes packaged plugin zips from release.infinilabs.com instead.
RUN bin/elasticsearch-plugin install --batch \
    https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-7.17.18.zip
