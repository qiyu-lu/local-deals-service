package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.OrderStateLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderStateLogMapper extends BaseMapper<OrderStateLog> {

    /** One statement for a whole batch's CREATE audit rows. */
    @Insert("<script>INSERT INTO order_state_log (order_no, from_status, to_status, event, operator) VALUES " +
            "<foreach collection=\"rows\" item=\"row\" separator=\",\">" +
            "(#{row.orderNo}, #{row.fromStatus}, #{row.toStatus}, #{row.event}, #{row.operator})" +
            "</foreach></script>")
    int insertCreatedBatch(@Param("rows") List<OrderStateLog> rows);
}
