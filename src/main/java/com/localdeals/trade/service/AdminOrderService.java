package com.localdeals.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The merchant back office's order list, scoped by the merchant_id snapshotted on the order
 * (idx(merchant_id, create_time)). M6 moves this read to an Elasticsearch model once orders
 * are sharded by user.
 */
@Service
public class AdminOrderService {

    private final TradeOrderMapper orderMapper;

    public AdminOrderService(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Page<TradeOrder> list(AdminPrincipal principal, String status, int current, int size) {
        QueryWrapper<TradeOrder> query = new QueryWrapper<>();
        if (!principal.isPlatform()) {
            query.eq("merchant_id", principal.getMerchantId());
        }
        if (StringUtils.hasText(status)) {
            query.eq("status", OrderStatus.valueOf(status.trim()).name());
        }
        query.orderByDesc("create_time");
        return orderMapper.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))), query);
    }
}
