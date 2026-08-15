package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.AdminAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AdminAccountMapper extends BaseMapper<AdminAccount> {

    @Select("SELECT DISTINCT p.code FROM tb_admin_permission p " +
            "JOIN tb_admin_role_permission rp ON rp.permission_code = p.code " +
            "JOIN tb_admin_role r ON r.id = rp.role_id AND r.status = 1 " +
            "JOIN tb_admin_account_role ar ON ar.role_id = r.id " +
            "WHERE ar.account_id = #{accountId} AND p.status = 1 ORDER BY p.code")
    List<String> selectPermissionCodes(@Param("accountId") Long accountId);

    @Select("SELECT DISTINCT r.code FROM tb_admin_role r " +
            "JOIN tb_admin_account_role ar ON ar.role_id = r.id " +
            "WHERE ar.account_id = #{accountId} AND r.status = 1 ORDER BY r.code")
    List<String> selectRoleCodes(@Param("accountId") Long accountId);

    @Insert("INSERT INTO tb_admin_account_role(account_id, role_id) VALUES(#{accountId}, #{roleId})")
    int insertAccountRole(@Param("accountId") Long accountId, @Param("roleId") Long roleId);

    @Update("UPDATE tb_admin_account SET last_login_time = CURRENT_TIMESTAMP WHERE id = #{accountId}")
    int touchLastLogin(@Param("accountId") Long accountId);

    @Update("UPDATE tb_admin_account SET password_hash = #{passwordHash}, " +
            "auth_version = auth_version + 1 WHERE id = #{accountId} " +
            "AND auth_version = #{authVersion} AND status = 1")
    int updatePasswordAndBumpVersion(@Param("accountId") Long accountId,
            @Param("authVersion") Integer authVersion,
            @Param("passwordHash") String passwordHash);

    @Update("<script>UPDATE tb_admin_account SET status = #{status}, " +
            "auth_version = auth_version + 1 WHERE id = #{accountId} " +
            "AND auth_version = #{authVersion} " +
            "<if test='merchantId != null'>AND merchant_id = #{merchantId}</if></script>")
    int updateStatusAndBumpVersion(@Param("accountId") Long accountId,
            @Param("status") Integer status,
            @Param("authVersion") Integer authVersion,
            @Param("merchantId") Long merchantId);
}
