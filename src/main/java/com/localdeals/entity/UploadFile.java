package com.localdeals.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tb_upload_file")
public class UploadFile implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "path", type = IdType.INPUT)
    private String path;
    private Long ownerUserId;
    private String status;
    private Long blogId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
