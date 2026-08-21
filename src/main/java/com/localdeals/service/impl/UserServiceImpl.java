package com.localdeals.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.dto.LoginFormDTO;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.User;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.UserMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.IUserService;
import com.localdeals.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.*;
import static com.localdeals.utils.RegexUtils.isCodeInvalid;
import static com.localdeals.utils.RegexUtils.isPhoneInvalid;
import static com.localdeals.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    private static final String INVALID_CODE_MESSAGE = "验证码不正确";
    private static final DefaultRedisScript<Long> ISSUE_LOGIN_CODE_SCRIPT;
    private static final DefaultRedisScript<Long> CONSUME_LOGIN_CODE_SCRIPT;

    static {
        ISSUE_LOGIN_CODE_SCRIPT = new DefaultRedisScript<>();
        ISSUE_LOGIN_CODE_SCRIPT.setLocation(new ClassPathResource("lua/issue_login_code.lua"));
        ISSUE_LOGIN_CODE_SCRIPT.setResultType(Long.class);

        CONSUME_LOGIN_CODE_SCRIPT = new DefaultRedisScript<>();
        CONSUME_LOGIN_CODE_SCRIPT.setLocation(new ClassPathResource("lua/consume_login_code.lua"));
        CONSUME_LOGIN_CODE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final LocalDealsMetrics metrics;

    @Value("${local-deals.auth.log-verification-code:false}")
    private boolean logVerificationCode;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate, LocalDealsMetrics metrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics;
    }

    @Override
    public Result sendCode(String phone, HttpSession session){
        // 1 号码校验
        if(isPhoneInvalid(phone)){//校验传入的电话号码，通过工具类中的方法
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.OTP_SEND,
                    LocalDealsMetrics.AuthResult.INVALID_INPUT);
            return Result.fail("号码不合法");
        }
        // 生成验证码，并通过 Lua 原子完成按手机号限流与验证码写入。
        String code = RandomUtil.randomNumbers(6);
        String codeKey = LOGIN_CODE_KEY + phone;
        String rateLimitKey = LOGIN_CODE_RATE_LIMIT_KEY + phone;
        String failureKey = LOGIN_CODE_FAILURE_KEY + phone;
        final Long issued;
        try {
            issued = stringRedisTemplate.execute(
                    ISSUE_LOGIN_CODE_SCRIPT,
                    Arrays.asList(rateLimitKey, codeKey, failureKey),
                    code,
                    String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_CODE_TTL)),
                    String.valueOf(LOGIN_CODE_RATE_LIMIT_TTL)
            );
        } catch (RuntimeException unavailable) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.OTP_SEND,
                    LocalDealsMetrics.AuthResult.UNAVAILABLE);
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCodes.AUTH_STATE_UNAVAILABLE, "认证状态暂不可用，请稍后重试");
        }
        if (!Long.valueOf(1L).equals(issued)) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.OTP_SEND,
                    LocalDealsMetrics.AuthResult.REJECTED);
            throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCodes.OTP_RATE_LIMITED, "验证码发送过于频繁，请稍后再试");
        }

        // 仅供显式开启的本地开发环境使用，生产默认绝不记录验证码。
        if (logVerificationCode) {
            log.warn("仅限本地开发：手机号 {} 的验证码为 {}", maskPhone(phone), code);
        }
        metrics.recordAuth(LocalDealsMetrics.AuthFlow.OTP_SEND,
                LocalDealsMetrics.AuthResult.SUCCESS);
        return Result.ok();
    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session){
        // 号码校验
        String phone = loginForm.getPhone();
        if(isPhoneInvalid(phone)){//校验传入的手机号码
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.INVALID_INPUT);
            return Result.fail("号码不合法");
        }
        String rawCode = loginForm.getCode();
        if (isCodeInvalid(rawCode)) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.INVALID_INPUT);
            return Result.fail(INVALID_CODE_MESSAGE);
        }
        String codeKey = LOGIN_CODE_KEY + phone;
        String failureKey = LOGIN_CODE_FAILURE_KEY + phone;
        final Long consumed;
        try {
            consumed = stringRedisTemplate.execute(
                    CONSUME_LOGIN_CODE_SCRIPT,
                    Arrays.asList(codeKey, failureKey),
                    rawCode,
                    String.valueOf(LOGIN_CODE_MAX_FAILURES)
            );
        } catch (RuntimeException unavailable) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.UNAVAILABLE);
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCodes.AUTH_STATE_UNAVAILABLE, "认证状态暂不可用，请稍后重试");
        }
        if (!Long.valueOf(1L).equals(consumed)) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.REJECTED);
            return Result.fail(INVALID_CODE_MESSAGE);
        }
        try {
            //根据号码查询用户，如果存在返回用户，不存在新建用户
            User user = lambdaQuery()
                    .eq(User::getPhone, phone)
                    .one();
            if(user == null){//如果用户不存在，查询不到，那么就创建新用户，进行保存
                user = generateUserWithphone(phone);
                save(user);
            }
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//复制不隐私的信息，不过我认为这里应该使用vo

            String token = UUID.randomUUID().toString(true);
            //Bean → Map（去掉敏感字段）Redis Hash 不支持存复杂对象，需要转成 String
            //把 UserDTO 对象转换成一个 Map<String, String>（Redis Hash 需要 String）
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                    new HashMap<>(),//这是目标 Map，也是 beanToMap 输出的容器
                    CopyOptions.create()//这是 Hutool 提供的“拷贝配置对象”，后面两个链式方法就是重点
                            .setIgnoreNullValue(true)//忽略所有 null 字段，不放到 Map 中
                            //把每个字段的值强制转成 String
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? "" : fieldValue.toString())
            );
            //写入 Redis（Hash 类型）+ 设置 TTL
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);// 设置有效期（30 分钟）
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.SUCCESS);
            //返回token到前端
            return Result.ok(token);
        } catch (RuntimeException failure) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                    LocalDealsMetrics.AuthResult.FAILURE);
            throw failure;
        }
    }

    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private User generateUserWithphone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        return user;
    }

    @Override
    public Result me(){
        return Result.ok(UserHolder.getUser());
    }

    @Override
    public Result logout(String token) {
        // 直接从 Redis 中删除用户信息
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        // 无需操作 UserHolder，因为拦截器的 afterCompletion 会统一清理
        return Result.ok();
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天，来设置第几位的状态
        int dayOfMonth = now.getDayOfMonth();
        //写入reids
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天，来设置第几位的状态
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(
                        key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType
                                        .unsigned(dayOfMonth)).valueAt(0)
                );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        //循环遍历
        int count = 0;
        while(true){
            //让这个数字与1做与运算，得到最后一位的bit位
            //判断最后一位是否为0
            //如果为0，未签到，结束
            //不为0，已经签到继续，计数器加 1
            //右移继续判断
            if((num & 1) == 0){
                break;
            }
            else{
                count++;
            }
            num = num >>> 1;
        }
        return Result.ok(count);
    }
}
