package com.localdeals.marketing;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.localdeals.platform.config.BusinessDateConfiguration;
import com.localdeals.platform.config.MybatisConfig;
import com.localdeals.merchant.mapper.AdminAccountMapper;
import com.localdeals.marketing.mapper.MarketingTagMapper;
import com.localdeals.marketing.mapper.MarketingTagMemberMapper;
import com.localdeals.merchant.mapper.MerchantMapper;
import com.localdeals.merchant.mapper.ShopMapper;
import com.localdeals.platform.mapper.SignMapper;
import com.localdeals.marketing.mapper.VoucherCampaignMapper;
import com.localdeals.marketing.mapper.VoucherGrantMapper;
import com.localdeals.marketing.mapper.VoucherGrantNotificationOutboxMapper;
import com.localdeals.trade.mapper.VoucherMapper;
import com.localdeals.marketing.service.MarketingAdminService;
import com.localdeals.marketing.service.VoucherCampaignUserService;
import com.localdeals.marketing.service.VoucherGrantService;
import com.localdeals.marketing.service.VoucherGrantTransactionService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.context.annotation.Import;

/**
 * Deliberately narrow M6A integration context.  It must not inherit the
 * application's component scan or its RocketMQ/Redis/ES/WebSocket setup.
 */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ImportAutoConfiguration(classes = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
@Import({BusinessDateConfiguration.class, MybatisConfig.class, MarketingAdminService.class, VoucherGrantService.class,
        VoucherGrantTransactionService.class, VoucherCampaignUserService.class,
        com.localdeals.trade.service.CouponIssuer.class, com.localdeals.trade.service.VerifyCodeGenerator.class})
public class M6aPersistenceTestConfiguration {

    @Bean
    public MapperFactoryBean<AdminAccountMapper> adminAccountMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(AdminAccountMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<MarketingTagMapper> marketingTagMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(MarketingTagMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<MarketingTagMemberMapper> marketingTagMemberMapper(
            SqlSessionFactory sqlSessionFactory) {
        return mapper(MarketingTagMemberMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<MerchantMapper> merchantMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(MerchantMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<ShopMapper> shopMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(ShopMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<SignMapper> signMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(SignMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<VoucherCampaignMapper> voucherCampaignMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherCampaignMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<VoucherMapper> voucherMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<com.localdeals.trade.mapper.UserCouponMapper> userCouponMapper(
            SqlSessionFactory sqlSessionFactory) {
        return mapper(com.localdeals.trade.mapper.UserCouponMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<VoucherGrantMapper> voucherGrantMapper(SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherGrantMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<VoucherGrantNotificationOutboxMapper> voucherGrantNotificationOutboxMapper(
            SqlSessionFactory sqlSessionFactory) {
        return mapper(VoucherGrantNotificationOutboxMapper.class, sqlSessionFactory);
    }

    private <T> MapperFactoryBean<T> mapper(Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<T> mapper = new MapperFactoryBean<>(mapperType);
        mapper.setSqlSessionFactory(sqlSessionFactory);
        return mapper;
    }
}
