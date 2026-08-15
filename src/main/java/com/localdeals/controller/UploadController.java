package com.localdeals.controller;

import com.localdeals.config.UploadProperties;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.service.UploadFileService;
import com.localdeals.utils.ManagedImagePath;
import com.localdeals.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    private static final Map<String, Set<String>> ALLOWED_IMAGE_TYPES;
    static {
        Map<String, Set<String>> types = new HashMap<>();
        types.put("jpg", Collections.singleton("image/jpeg"));
        types.put("jpeg", Collections.singleton("image/jpeg"));
        types.put("png", Collections.singleton("image/png"));
        ALLOWED_IMAGE_TYPES = Collections.unmodifiableMap(types);
    }

    private final UploadProperties uploadProperties;
    private final UploadFileService uploadFileService;

    public UploadController(UploadProperties uploadProperties, UploadFileService uploadFileService) {
        this.uploadProperties = uploadProperties;
        this.uploadFileService = uploadFileService;
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        String validationError = validateImage(image);
        if (validationError != null) {
            return Result.fail(validationError);
        }
        try {
            String extension = extensionOf(image.getOriginalFilename());
            Path imageRoot = prepareImageRoot();
            Path relativePath = createRelativeFileName(extension);
            Path directory = imageRoot.resolve(relativePath).getParent();
            Files.createDirectories(directory);

            // Resolve symlinks in existing directories before writing the file.
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(imageRoot)) {
                return Result.fail("非法的上传路径");
            }
            Path target = realDirectory.resolve(relativePath.getFileName()).normalize();
            image.transferTo(target.toFile());

            String relativeName = relativePath.toString().replace('\\', '/');
            try {
                uploadFileService.registerTemporary(relativeName, user.getId());
            } catch (RuntimeException e) {
                Files.deleteIfExists(target);
                throw e;
            }

            String publicPath = "/" + relativeName;
            log.debug("图片上传成功，path={}", publicPath);
            return Result.ok(publicPath);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        String relativeName = ManagedImagePath.normalize(filename);
        if (relativeName == null) {
            return Result.fail("错误的文件名称");
        }
        try {
            Path imageRoot = prepareImageRoot();
            Path relativePath = Paths.get(relativeName).normalize();
            Path target = imageRoot.resolve(relativePath).normalize();
            if (!target.startsWith(imageRoot) || target.getParent() == null) {
                return Result.fail("错误的文件名称");
            }

            if (!Files.exists(target)) {
                uploadFileService.removeMissingTemporary(relativeName, user.getId());
                return Result.ok();
            }
            Path realParent = target.getParent().toRealPath();
            if (!realParent.startsWith(imageRoot)) {
                return Result.fail("错误的文件名称");
            }
            Path realTarget = realParent.resolve(target.getFileName());
            if (Files.isSymbolicLink(realTarget) || Files.isDirectory(realTarget)) {
                return Result.fail("错误的文件名称");
            }
            if (!uploadFileService.claimTemporaryDeletion(relativeName, user.getId())) {
                return Result.fail("图片不存在或无权删除");
            }
            try {
                Files.deleteIfExists(realTarget);
                uploadFileService.completeTemporaryDeletion(relativeName, user.getId());
            } catch (IOException | RuntimeException e) {
                uploadFileService.releaseTemporaryDeletion(relativeName, user.getId());
                throw e;
            }
            return Result.ok();
        } catch (IOException e) {
            throw new RuntimeException("文件删除失败", e);
        }
    }

    private String validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return "请选择要上传的图片";
        }
        if (image.getSize() > uploadProperties.getMaxImageSize().toBytes()) {
            return "图片大小不能超过 " + uploadProperties.getMaxImageSize();
        }
        String extension = extensionOf(image.getOriginalFilename());
        Set<String> allowedMimeTypes = ALLOWED_IMAGE_TYPES.get(extension);
        String contentType = image.getContentType();
        if (allowedMimeTypes == null || contentType == null ||
                !allowedMimeTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            return "仅支持 JPG、JPEG、PNG 图片";
        }
        try {
            if (!hasExpectedSignature(image, extension)) {
                return "图片内容与文件类型不匹配";
            }
        } catch (IOException e) {
            return "无法读取上传的图片";
        }
        return null;
    }

    private boolean hasExpectedSignature(MultipartFile image, String extension) throws IOException {
        byte[] header = new byte[8];
        int length;
        try (InputStream input = image.getInputStream()) {
            length = input.read(header);
        }
        if ("png".equals(extension)) {
            byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            return length == png.length && Arrays.equals(header, png);
        }
        return length >= 3 && (header[0] & 0xff) == 0xff &&
                (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Path createRelativeFileName(String extension) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return Paths.get("blogs", String.valueOf(d1), String.valueOf(d2), name + "." + extension);
    }

    private Path prepareImageRoot() throws IOException {
        Path configuredRoot = uploadProperties.getImageRoot().toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        return configuredRoot.toRealPath();
    }

}
