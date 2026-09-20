package com.localdeals.platform.config;

import com.localdeals.merchant.interceptor.AdminAuthorizationInterceptor;
import com.localdeals.merchant.interceptor.AdminSessionInterceptor;
import com.localdeals.platform.interceptor.LoginInterceptor;
import com.localdeals.platform.interceptor.RefreshTokenInterceptor;
import com.localdeals.merchant.service.AdminSessionService;
import com.localdeals.trade.interceptor.SeckillSoldOutInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration //表示这是一个配置类，会在项目启动时加载
//WebMvcConfigurer 允许你向 Spring MVC 框架中添加自定义配置
public class WebConfig implements WebMvcConfigurer {
    /**
     * Public catalogue/content reads. LoginInterceptor additionally requires GET,
     * so a write endpoint can never become public merely because it shares a path.
     */
    public static final List<String> PUBLIC_GET_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/shop/{id:\\d+}",
            "/shop/of/type",
            "/shop/search",
            "/shop-type/list",
            "/blog/hot",
            "/blog/{id:\\d+}",
            "/blog/likes/{id:\\d+}",
            "/blog/of/user",
            "/blog/of/shop",
            "/blog/search",
            "/voucher/list/{shopId:\\d+}"
    ));
    public static final List<String> PUBLIC_POST_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/user/code",
            "/user/login",
            // Called by the payment channel; authenticated by the HMAC signature.
            "/payment/callback",
            "/payment/refund-callback",
            // The mock channel's cashier stands in for a third-party page.
            "/mock-channel/payments/{payNo}/pay"
    ));

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AdminSessionService adminSessionService;

    @Autowired
    private ObjectProvider<SeckillSoldOutInterceptor> seckillSoldOutInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // L1 of the seckill funnel runs before authentication: a sold-out answer needs no Redis.
        seckillSoldOutInterceptor.ifAvailable(interceptor -> registry.addInterceptor(interceptor)
                .addPathPatterns("/voucher-order/seckill/{id}")
                .order(-1));
        //每一个 HTTP 请求进入 Controller 之前，都要经过 LoginInterceptor 的 preHandle()
        registry.addInterceptor(new LoginInterceptor(PUBLIC_GET_PATHS, PUBLIC_POST_PATHS))
                .excludePathPatterns("/admin/**")
                .order(1);
        //拦截一切，设置执行顺序，优先级
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/**")
                .order(0);
        registry.addInterceptor(new AdminSessionInterceptor(adminSessionService))
                .addPathPatterns("/admin/**")
                .order(0);
        registry.addInterceptor(new AdminAuthorizationInterceptor())
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/auth/login")
                .order(1);
    }

    /**
     * This API answers JSON, and no dependency gets to change that by arriving on the classpath.
     *
     * <p>ShardingSphere's metadata repository needs jackson-dataformat-xml, and Spring builds an
     * XML converter ahead of the JSON one whenever it sees XmlMapper. A request that sends no
     * Accept header, or asks for {@code *\/*} as every browser does, then gets
     * {@code <Result><success>true</success>...}. Dropping the dependency is not an option — the
     * repository fails to start without it — so the converters go instead.</p>
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(WebConfig::writesXml);
    }

    /**
     * True only for a converter that names an XML type itself. Matching on {@code canWrite}
     * instead would also take out the byte-array and string converters, which claim
     * {@code *}{@code /*} and are needed for every non-JSON response the API still makes.
     */
    private static boolean writesXml(HttpMessageConverter<?> converter) {
        return converter.getSupportedMediaTypes().stream()
                .anyMatch(type -> "xml".equals(type.getSubtype()) || "xml".equals(type.getSubtypeSuffix()));
    }
}
