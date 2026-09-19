package com.localdeals.platform.testsupport;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.test.util.ReflectionTestUtils;

/** Wires a mocked mapper into a MyBatis-Plus {@code ServiceImpl} without a Spring context. */
public final class MybatisPlusMocks {

    private MybatisPlusMocks() {
    }

    /**
     * MyBatis-Plus 3.5 resolves a service's entity class from the mapper's MyBatis proxy the first
     * time a wrapper is built; a Mockito mock is not such a proxy, so the class is set explicitly.
     */
    public static <T> void injectMapper(Object service, BaseMapper<T> mapper, Class<T> entityClass) {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", entityClass);
    }
}
