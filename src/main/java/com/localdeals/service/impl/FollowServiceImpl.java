package com.localdeals.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Follow;
import com.localdeals.mapper.FollowMapper;
import com.localdeals.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.service.IUserService;
import com.localdeals.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.localdeals.utils.RedisConstants.FOLLOWED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1. 获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWED_KEY + userId;
        //2.判断到底需要关注还是取关
        if(isFollow){
            //3.要关注，新增数据进行关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                //把关注的用户的id放入redis的set集合中
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }
        else{
            //4.要取消关注，删除进行取关
            boolean isSuccess =  remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(isSuccess)
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //查询是否关注
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count);
    }

    @Override
    public Result getMyFollows() {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWED_KEY + userId;
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = members.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result getCommonFollow(Long id) {
        //要获得目标用户和当前用户的交集
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOWED_KEY + userId;

        String key2 = FOLLOWED_KEY + id;

        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet()
                .intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
