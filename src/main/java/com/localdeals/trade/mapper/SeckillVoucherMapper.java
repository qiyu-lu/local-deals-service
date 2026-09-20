package com.localdeals.trade.mapper;

import com.localdeals.trade.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * Takes a whole consumer batch off one voucher's stock in a single statement. The
     * {@code stock >= n} guard keeps the row from going negative; zero affected rows means the
     * batch must be replayed one order at a time.
     */
    @Update("UPDATE tb_seckill_voucher SET stock = stock - #{count} " +
            "WHERE voucher_id = #{voucherId} AND stock >= #{count}")
    int decrementStock(@Param("voucherId") long voucherId, @Param("count") int count);
}
