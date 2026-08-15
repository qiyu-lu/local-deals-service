package com.localdeals.service;

import com.localdeals.entity.UploadFile;
import com.localdeals.mapper.UploadFileMapper;
import com.localdeals.utils.ManagedImagePath;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UploadFileService {

    static final String STATUS_TEMP = "TEMP";
    static final String STATUS_DELETING = "DELETING";
    static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final int MAX_BLOG_IMAGES = 9;

    private final UploadFileMapper uploadFileMapper;

    public UploadFileService(UploadFileMapper uploadFileMapper) {
        this.uploadFileMapper = uploadFileMapper;
    }

    public void registerTemporary(String path, Long ownerUserId) {
        UploadFile upload = new UploadFile();
        upload.setPath(path);
        upload.setOwnerUserId(ownerUserId);
        upload.setStatus(STATUS_TEMP);
        if (uploadFileMapper.insert(upload) != 1) {
            throw new IllegalStateException("上传记录保存失败");
        }
    }

    public boolean isTemporaryOwner(String path, Long ownerUserId) {
        UploadFile upload = uploadFileMapper.selectById(path);
        return upload != null && ownerUserId != null && ownerUserId.equals(upload.getOwnerUserId()) &&
                STATUS_TEMP.equals(upload.getStatus());
    }

    public boolean claimTemporaryDeletion(String path, Long ownerUserId) {
        return uploadFileMapper.claimTemporaryDeletion(path, ownerUserId) == 1;
    }

    public void completeTemporaryDeletion(String path, Long ownerUserId) {
        uploadFileMapper.deleteByOwnerAndStatus(path, ownerUserId, STATUS_DELETING);
    }

    public void removeMissingTemporary(String path, Long ownerUserId) {
        uploadFileMapper.deleteByOwnerAndStatus(path, ownerUserId, STATUS_TEMP);
    }

    public void releaseTemporaryDeletion(String path, Long ownerUserId) {
        uploadFileMapper.releaseTemporaryDeletion(path, ownerUserId);
    }

    public List<String> validateTemporaryImages(String images, Long ownerUserId) {
        if (images == null || images.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] rawPaths = images.split(",");
        if (rawPaths.length > MAX_BLOG_IMAGES) {
            throw new IllegalArgumentException("笔记最多上传 9 张图片");
        }
        Set<String> uniquePaths = new LinkedHashSet<>();
        for (String rawPath : rawPaths) {
            String path = ManagedImagePath.normalize(rawPath);
            if (path == null || !uniquePaths.add(path) || !isTemporaryOwner(path, ownerUserId)) {
                throw new IllegalArgumentException("图片无效或无权使用");
            }
        }
        return new ArrayList<>(uniquePaths);
    }

    public void markPublished(List<String> paths, Long ownerUserId, Long blogId) {
        for (String path : paths) {
            if (uploadFileMapper.markPublished(path, ownerUserId, blogId) != 1) {
                throw new IllegalStateException("图片发布状态发生冲突");
            }
        }
    }
}
