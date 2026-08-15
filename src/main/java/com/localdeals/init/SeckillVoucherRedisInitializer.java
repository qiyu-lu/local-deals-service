package com.localdeals.init;

import com.localdeals.entity.SeckillVoucher;
import com.localdeals.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Backfills Redis admission state for seckill vouchers created before Redis metadata preheating
 * was introduced.
 *
 * <p>The initializer deliberately fails application startup when the database cannot be read,
 * a database row is invalid, or Redis cannot apply the backfill. Serving traffic with an
 * incomplete admission data set would make availability depend on startup timing. The Lua
 * operation is idempotent, so the instance can safely resume after the dependency or data issue
 * has been fixed.</p>
 */
@Slf4j
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeckillVoucherRedisInitializer implements ApplicationRunner {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DefaultRedisScript<Long> BACKFILL_SCRIPT;

    static {
        BACKFILL_SCRIPT = new DefaultRedisScript<>();
        BACKFILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill_voucher_backfill.lua"));
        BACKFILL_SCRIPT.setResultType(Long.class);
    }

    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;

    public SeckillVoucherRedisInitializer(ISeckillVoucherService seckillVoucherService,
                                          StringRedisTemplate stringRedisTemplate) {
        this.seckillVoucherService = seckillVoucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializeFromDatabase();
    }

    int initializeFromDatabase() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers == null) {
            throw new IllegalStateException("Seckill voucher query returned null");
        }

        validateAll(vouchers);

        int changedVouchers = 0;
        for (SeckillVoucher voucher : vouchers) {
            Long result = stringRedisTemplate.execute(
                    BACKFILL_SCRIPT,
                    Arrays.asList(
                            SECKILL_STOCK_KEY + voucher.getVoucherId(),
                            SECKILL_META_KEY + voucher.getVoucherId()),
                    voucher.getStock().toString(),
                    Long.toString(voucher.getBeginTime().atZone(BUSINESS_ZONE).toEpochSecond()),
                    Long.toString(voucher.getEndTime().atZone(BUSINESS_ZONE).toEpochSecond())
            );
            if (result == null) {
                throw new IllegalStateException(
                        "Redis returned no result while backfilling voucher " + voucher.getVoucherId());
            }
            if (result != 0L) {
                changedVouchers++;
            }
        }

        log.info("Seckill Redis backfill completed: scanned={}, changed={}",
                vouchers.size(), changedVouchers);
        return changedVouchers;
    }

    private void validateAll(List<SeckillVoucher> vouchers) {
        int invalidCount = 0;
        for (int index = 0; index < vouchers.size(); index++) {
            SeckillVoucher voucher = vouchers.get(index);
            List<String> violations = validate(voucher);
            if (!violations.isEmpty()) {
                invalidCount++;
                log.error("Invalid seckill voucher blocks startup: rowIndex={}, voucherId={}, violations={}",
                        index, voucher == null ? null : voucher.getVoucherId(), violations);
            }
        }
        if (invalidCount > 0) {
            throw new IllegalStateException(
                    "Refusing to start with " + invalidCount + " invalid seckill voucher record(s)");
        }
    }

    private List<String> validate(SeckillVoucher voucher) {
        if (voucher == null) {
            return Collections.singletonList("record is null");
        }

        java.util.ArrayList<String> violations = new java.util.ArrayList<>();
        if (voucher.getVoucherId() == null) {
            violations.add("voucherId is null");
        }
        if (voucher.getStock() == null || voucher.getStock() < 0) {
            violations.add("stock is null or negative");
        }
        if (voucher.getBeginTime() == null) {
            violations.add("beginTime is null");
        }
        if (voucher.getEndTime() == null) {
            violations.add("endTime is null");
        }
        if (voucher.getBeginTime() != null && voucher.getEndTime() != null
                && !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            violations.add("beginTime must be before endTime");
        }
        return violations;
    }
}
