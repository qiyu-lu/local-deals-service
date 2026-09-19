package com.localdeals.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.trade.entity.TradeOrder;
import org.springframework.stereotype.Service;

/** The merchant back office's order list. */
@Service
public class AdminOrderService {

    public Page<TradeOrder> list(AdminPrincipal principal, String status, int current, int size) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
