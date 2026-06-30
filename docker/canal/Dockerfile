FROM canal/canal-server:v1.1.7

USER root
# Fix A: create symlink so bin/plugin -> ../plugin resolves correctly
RUN ln -s /home/admin/canal-server/plugin /home/admin/canal-server/bin/plugin || true

# Fix B: patch instance.properties to use mysql-sync-topic
RUN sed -i 's/^canal.mq.topic=example/canal.mq.topic=mysql-sync-topic/' \
    /home/admin/canal-server/conf/example/instance.properties
