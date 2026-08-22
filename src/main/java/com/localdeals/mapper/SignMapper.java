package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.SignRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface SignMapper extends BaseMapper<SignRecord> {
    @Select("SELECT `date` FROM tb_sign WHERE user_id=#{userId} " +
            "AND `date` <= #{date} ORDER BY `date` DESC")
    List<LocalDate> selectDatesUntil(@Param("userId") Long userId,
            @Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM tb_sign WHERE user_id=#{userId} AND `date`=#{date}")
    int countByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);
}
