package com.hmdp.interctptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class RefreshTokenInterceptor implements HandlerInterceptor {
    //不推荐在拦截器中 @Autowired
    //因为没有把 LoginInterceptor 注册成 Spring Bean
    //因为在拦截器的配置中使用了 new LoginInterceptor()，这是“手动创建对象”，不是 Spring 创建的
    //不是 Spring 管理的，因此 Spring 不会给它自动注入依赖 所以里面的 StringRedisTemplate 会是 null
    //正确做法 在 WebConfig（Spring 管理的 Bean）中注入依赖，然后传给拦截器
    private StringRedisTemplate stringRedisTemplate;
    //加一个这个函数，用于传入注入的redis
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从 Session 获取用户，检查 Session 中是否存有用户
        // 每次请求进来时，都有一个 session id（在 cookie 中）
        // 根据 session id 找到服务器中的 session 对象
        // session 中存了 "user" 字段（登录时放进去的） 如果 user == null → 表示没登录。
//        UserDTO user = (UserDTO) request.getSession().getAttribute("user");
//        if (user == null) {
//            response.setStatus(401);
//            return false;
//        }
        // 保存到 ThreadLocal，后续可以获取
        // 已登录，将用户信息保存到 ThreadLocal
//        UserHolder.saveUser(user);


        // 获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        // 基于这个token 作为key获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries("login:token:" + token);
        if(userMap.isEmpty()){
            return true;
        }

        // 由于之前存入redis中的是hash格式的，因此这里取出的hash类型需要转化为 dto 类，然后再存入
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        // 刷新token的有效期，为了使只有在登录状态时就一直刷新有效期，否则会慢慢过期，不然会出现，明明用户在登录状态但到了时间仍然退出登录了
        stringRedisTemplate.expire("login:token:" + token, 30, TimeUnit.MINUTES);
        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理 ThreadLocal  请求结束后清除线程数据
        UserHolder.removeUser();
    }
}
