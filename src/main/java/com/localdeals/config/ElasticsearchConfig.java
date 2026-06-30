package com.localdeals.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.RestClients;
import org.springframework.data.elasticsearch.config.AbstractElasticsearchConfiguration;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;

@Configuration
public class ElasticsearchConfig extends AbstractElasticsearchConfiguration {

    @Value("${spring.elasticsearch.rest.uris:http://localhost:9200}")
    private String esUri;

    @Override
    public RestHighLevelClient elasticsearchClient() {
        ClientConfiguration config = ClientConfiguration.builder()
                .connectedTo(esUri.replace("http://", "").replace("https://", ""))
                .withConnectTimeout(5000)
                .withSocketTimeout(10000)
                .build();
        return RestClients.create(config).rest();
    }

    /**
     * 覆盖父类方法，将声明返回类型收窄为 ElasticsearchRestTemplate（而非 ElasticsearchOperations 接口），
     * 这样 @Autowired private ElasticsearchRestTemplate 字段才能按类型匹配到该 bean。
     * 父类 AbstractElasticsearchConfiguration#elasticsearchOperations 实际创建的就是 ElasticsearchRestTemplate 实例，
     * 但其方法声明的返回类型是接口 ElasticsearchOperations，会导致按具体类型自动装配失败。
     */
    @Override
    @Bean(name = {"elasticsearchOperations", "elasticsearchTemplate"})
    public ElasticsearchRestTemplate elasticsearchOperations(ElasticsearchConverter elasticsearchConverter) {
        return new ElasticsearchRestTemplate(elasticsearchClient(), elasticsearchConverter);
    }
}
