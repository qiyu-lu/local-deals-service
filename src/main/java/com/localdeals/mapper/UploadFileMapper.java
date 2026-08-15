package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.UploadFile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UploadFileMapper extends BaseMapper<UploadFile> {

    @Update("UPDATE tb_upload_file SET status = 'PUBLISHED', blog_id = #{blogId} " +
            "WHERE path = #{path} AND owner_user_id = #{ownerUserId} AND status = 'TEMP'")
    int markPublished(@Param("path") String path,
            @Param("ownerUserId") Long ownerUserId,
            @Param("blogId") Long blogId);

    @Update("UPDATE tb_upload_file SET status = 'DELETING' " +
            "WHERE path = #{path} AND owner_user_id = #{ownerUserId} AND status = 'TEMP'")
    int claimTemporaryDeletion(@Param("path") String path, @Param("ownerUserId") Long ownerUserId);

    @Delete("DELETE FROM tb_upload_file " +
            "WHERE path = #{path} AND owner_user_id = #{ownerUserId} AND status = #{status}")
    int deleteByOwnerAndStatus(@Param("path") String path,
            @Param("ownerUserId") Long ownerUserId,
            @Param("status") String status);

    @Update("UPDATE tb_upload_file SET status = 'TEMP' " +
            "WHERE path = #{path} AND owner_user_id = #{ownerUserId} AND status = 'DELETING'")
    int releaseTemporaryDeletion(@Param("path") String path, @Param("ownerUserId") Long ownerUserId);
}
