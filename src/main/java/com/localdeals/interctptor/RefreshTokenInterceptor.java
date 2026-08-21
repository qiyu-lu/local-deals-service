package com.localdeals.interctptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.localdeals.dto.UserDTO;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;


public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    //加一个这个函数，用于传入注入的redis
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        try {
            // 基于这个token 作为key获取redis中的用户
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
            if(userMap.isEmpty()){
                return true;
            }

            // 由于之前存入redis中的是hash格式的，因此这里取出的hash类型需要转化为 dto 类，然后再存入
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            UserHolder.saveUser(userDTO);
            // 刷新token的有效期，为了使只有在登录状态时就一直刷新有效期，否则会慢慢过期，不然会出现，明明用户在登录状态但到了时间仍然退出登录了
            stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
            // 放行
            return true;
        } catch (RuntimeException e) {
            UserHolder.removeUser();
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCodes.AUTH_STATE_UNAVAILABLE,
                    "登录状态暂时不可用，请稍后重试");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理 ThreadLocal  请求结束后清除线程数据
        UserHolder.removeUser();
    }
}
