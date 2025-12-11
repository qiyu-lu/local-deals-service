package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import javax.xml.ws.Action;

import java.time.LocalDateTime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RegexUtils.isPhoneInvalid;

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
    @Autowired
    private HttpSession session;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session){
        // 1 号码校验
        if(isPhoneInvalid(phone)){//校验传入的电话号码，通过工具类中的方法
            return Result.fail("号码不合法");
        }
        // 生成验证码 这里是使用随机数方法生成一个6位数的验证码
        String code = RandomUtil.randomNumbers(6);

        //存入session  将验证码存入传入的session中
        //session.setAttribute("code", code);
        //这里不是选择将验证码存入session中，而是选择存入redis中
        String codeKey = "login:code:" + phone;
        stringRedisTemplate.opsForValue().set(codeKey, code, 2, TimeUnit.MINUTES);

        //打印或者发送短信
        log.debug("验证码是：" + code);
        return Result.ok();
    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session){
        // 号码校验
        String phone = loginForm.getPhone();
        if(isPhoneInvalid(phone)){//校验传入的手机号码
            return Result.fail("号码不合法");
        }
        // 取出验证码 用户输入的验证吗
        String rawCode = loginForm.getCode();
        //和之前发送的验证码进行比较
        //session 中是之前 后端发送给用户并存session中的验证码，而loginFromDTO中的code是用户手动输入的验证码
//        if(rawCode ==null || !rawCode.equals(session.getAttribute("code"))){
//            return Result.fail("验证码不正确");
//        }
        //从redis中取出验证码
        if(rawCode ==null || !rawCode.equals(stringRedisTemplate.opsForValue().get("login:code:" + phone))){
            return Result.fail("验证码不正确");
        }
        //根据号码查询用户，如果存在返回用户，不存在新建用户
        // 这里之所以不需要写sql语句，是因为这个类 继承了 MyBatis-Plus 的 ServiceImpl<M extends BaseMapper<T>, T>
        // UserMapper 继承 BaseMapper，注入的UserMapper 通过 baseMapper 来执行 SQL，
        // 而为什么User 类可以映射到数据库表，因为在User 类上加了注解 @TableName("tb_user")，
        // 这个注解告诉 MyBatis-Plus，User 这个实体类对应数据库的 tb_user 表，MP 自动根据字段名生成 SQL
        User user = lambdaQuery()
                .eq(User::getPhone, phone)
                .one();
        if(user == null){//如果用户不存在，查询不到，那么就创建新用户，进行保存
            user = generateUserWithphone(phone);
            save(user);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//复制不隐私的信息，不过我认为这里应该使用vo
//        session.setAttribute("user", userDTO);//将用户部分信息存入session
        //这里选择采用将用户保存到redis中，应该以哈希类型进行存储，Hash 可部分更新字段，不需要整个 JSON 反序列化 / 序列化，性能更好
        //例如刷新 token 续期，只需要更新 expire_time 字段。
        // 随机生成 token
        String token = UUID.randomUUID().toString(true);
        //Bean → Map（去掉敏感字段）Redis Hash 不支持存复杂对象，需要转成 String
        //把 UserDTO 对象转换成一个 Map<String, String>（Redis Hash 需要 String）
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),//这是目标 Map，也就是 beanToMap 输出的容器
                CopyOptions.create()//这是 Hutool 提供的“拷贝配置对象”，后面两个链式方法就是重点
                        .setIgnoreNullValue(true)//忽略所有 null 字段，不放到 Map 中
                        //把每个字段的值强制转成 String
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        //写入 Redis（Hash 类型）+ 设置 TTL
        String tokenKey = "login:token:" + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);// 设置有效期（30 分钟）

        //返回token到前端
        return Result.ok(token);
    }

    private User generateUserWithphone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        return user;
    }

    @Override
    public Result me(){
        return Result.ok(UserHolder.getUser());
    }
}
