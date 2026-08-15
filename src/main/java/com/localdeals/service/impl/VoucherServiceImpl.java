package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.dto.Result;
import com.localdeals.entity.Voucher;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.entity.SeckillVoucher;
import com.localdeals.service.ISeckillVoucherService;
import com.localdeals.service.IVoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherServiceImpl.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        validateSeckillVoucher(voucher);
        // 1. 保存优惠券
        voucher.setStatus(1);  // 上架，否则 queryVoucherOfShop 过滤掉
        voucher.setType(1);    // 秒杀券
        if (!save(voucher)) {
            throw new IllegalStateException("秒杀券主记录保存失败");
        }

        // 2. 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀券库存记录保存失败");
        }

        // 3. 只有 MySQL 事务成功提交后才预热 Redis，避免回滚后留下幽灵库存。
        Long voucherId = Objects.requireNonNull(voucher.getId(), "voucher id must not be null");
        Integer stock = Objects.requireNonNull(voucher.getStock(), "seckill stock must not be null");
        long beginAt = Objects.requireNonNull(voucher.getBeginTime(), "seckill begin time must not be null")
                .atZone(BUSINESS_ZONE)
                .toEpochSecond();
        long endAt = Objects.requireNonNull(voucher.getEndTime(), "seckill end time must not be null")
                .atZone(BUSINESS_ZONE)
                .toEpochSecond();
        registerRedisPreheatAfterCommit(voucherId, stock, beginAt, endAt);
    }

    private void validateSeckillVoucher(Voucher voucher) {
        if (voucher == null || voucher.getStock() == null || voucher.getStock() < 0) {
            throw new IllegalArgumentException("秒杀库存不能为负数");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null ||
                !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            throw new IllegalArgumentException("秒杀开始时间必须早于结束时间");
        }
    }

    private void registerRedisPreheatAfterCommit(Long voucherId, Integer stock, long beginAt, long endAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("seckill voucher must be created within a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.opsForValue().set(
                            SECKILL_STOCK_KEY + voucherId,
                            stock.toString()
                    );
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("status", "ACTIVE");
                    metadata.put("beginAt", Long.toString(beginAt));
                    metadata.put("endAt", Long.toString(endAt));
                    stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + voucherId, metadata);
                } catch (RuntimeException e) {
                    // DB 已经提交：预热失败应由监控/补偿修复，不能把已成功的创建伪装成失败。
                    log.error("Failed to preheat seckill voucher in Redis after commit, voucherId={}", voucherId, e);
                }
            }
        });
    }

}
