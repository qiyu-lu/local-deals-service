package com.localdeals.marketing;

import com.localdeals.config.VoucherBatchProperties;
import com.localdeals.mapper.VoucherBatchItemMapper;
import com.localdeals.mapper.VoucherBatchJobMapper;
import com.localdeals.service.VoucherBatchJobService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Narrow M6C business context: MySQL persistence and batch orchestration only. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@Import({M6aPersistenceTestConfiguration.class, VoucherBatchJobService.class, VoucherBatchProperties.class})
public class M6cPersistenceTestConfiguration {

    @Bean
    public MapperFactoryBean<VoucherBatchJobMapper> voucherBatchJobMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherBatchJobMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<VoucherBatchItemMapper> voucherBatchItemMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherBatchItemMapper.class, sqlSessionFactory);
    }

    private <T> MapperFactoryBean<T> mapper(Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<T> mapper = new MapperFactoryBean<>(mapperType);
        mapper.setSqlSessionFactory(sqlSessionFactory);
        return mapper;
    }
}
