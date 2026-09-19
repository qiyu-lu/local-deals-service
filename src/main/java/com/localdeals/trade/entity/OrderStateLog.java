package com.localdeals.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Append-only audit trail of order transitions, written in the transition's transaction. */
@Data
@NoArgsConstructor
@TableName("order_state_log")
public class OrderStateLog {
    public static final String EVENT_CREATE = "CREATE";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderNo;
    private OrderStatus fromStatus;
    private OrderStatus toStatus;
    private String event;
    private String operator;
    private LocalDateTime createTime;

    public OrderStateLog(Long orderNo, OrderStatus fromStatus, OrderStatus toStatus,
                         String event, String operator) {
        this.orderNo = orderNo;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.event = event;
        this.operator = operator;
    }
}
