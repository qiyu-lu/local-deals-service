package com.localdeals.config;

import com.localdeals.interctptor.AdminAuthorizationInterceptor;
import com.localdeals.interctptor.AdminSessionInterceptor;
import com.localdeals.interctptor.LoginInterceptor;
import com.localdeals.interctptor.RefreshTokenInterceptor;
import com.localdeals.service.AdminSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
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
            "/shop/of/name",
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
            "/user/login"
    ));

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AdminSessionService adminSessionService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
}
