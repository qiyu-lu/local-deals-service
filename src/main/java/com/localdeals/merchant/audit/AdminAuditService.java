package com.localdeals.merchant.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.merchant.mapper.AdminAuditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {

    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /** Own transaction: a failed operation is audited too, whatever happened to its transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminAuditLog log) {
        mapper.insert(log);
    }

    /** Platform accounts see everything; a merchant sees only its own accounts' actions. */
    public Page<AdminAuditLog> list(AdminPrincipal principal, int current, int size) {
        QueryWrapper<AdminAuditLog> query = new QueryWrapper<>();
        if (!principal.isPlatform()) {
            query.eq("merchant_id", principal.getMerchantId());
        }
        query.orderByDesc("id");
        return mapper.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))), query);
    }
}
