package com.hmdp.config;

import com.hmdp.interctptor.LoginInterceptor;
import com.hmdp.interctptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration //表示这是一个配置类，会在项目启动时加载
//WebMvcConfigurer 允许你向 Spring MVC 框架中添加自定义配置
public class WebConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //每一个 HTTP 请求进入 Controller 之前，都要经过 LoginInterceptor 的 preHandle()
        registry.addInterceptor(new LoginInterceptor())
                //设置拦截器的放行路径,以下这些路径不需要拦截
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",//不需要登录就可以访问 放行
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/voucher/**"
                ).order(1);
        //拦截一切，设置执行顺序，优先级
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
