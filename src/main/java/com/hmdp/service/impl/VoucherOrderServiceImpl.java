package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service

public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker  redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Value("${server.port}")
    private String serverPort;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId){
        //查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //判断秒杀时间
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()))
            return Result.fail("秒杀未开始");
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now()))
            return Result.fail("秒杀已结束");
        //当前时间在秒杀时间范围内，则判断库存是否充足
        if(seckillVoucher.getStock() < 1)
            return Result.fail("秒杀券库存不足");
        //如果库存充足，则扣减库存
        //一人一单校验
        Long userId = UserHolder.getUser().getId();
        // 3. redis锁实现一人一单
        String lockKey = "order:" + userId;
        org.redisson.api.RLock lock = redissonClient.getLock("lock:" + lockKey);
        boolean success = lock.tryLock();

        if(!success){
            return Result.fail("不能重复多次下单");//这里抢购就不采用重复进行的策略，而是采用直接终止
        }
        try{
            //  必须通过代理对象调用
            log.info("进入 synchronized，port={}, userId={}",
                    serverPort, userId);
            return voucherOrderService.createVoucherOrder(voucherId);
        }
        finally{
            lock.unlock();
        }

    }
    /**
     * 真正创建订单的方法（负责事务）
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId){

        Long userId = UserHolder.getUser().getId();

        // 4. 一人一单校验
        boolean exists = this.lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count() > 0;

        if (exists) {
            return Result.fail("不能重复下单");
        }

        // 5. 扣减库存（乐观锁）
        boolean success = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();

        if (!success) {
            return Result.fail("库存不足");
        }

        // 6. 创建订单
        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        this.save(order);

        return Result.ok(order.getId());
    }
}
