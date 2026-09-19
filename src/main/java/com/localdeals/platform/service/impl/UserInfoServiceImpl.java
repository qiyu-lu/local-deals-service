package com.localdeals.platform.service.impl;

import com.localdeals.platform.entity.UserInfo;
import com.localdeals.platform.mapper.UserInfoMapper;
import com.localdeals.platform.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
