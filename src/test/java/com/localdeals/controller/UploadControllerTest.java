package com.localdeals.controller;

import com.localdeals.config.UploadProperties;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.service.UploadFileService;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadControllerTest {

    private static final byte[] PNG_HEADER = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    @TempDir
    Path imageRoot;

    private UploadController controller;
    private UploadFileService uploadFileService;

    @BeforeEach
    void setUp() {
        UploadProperties properties = new UploadProperties();
        properties.setImageRoot(imageRoot);
        properties.setMaxImageSize(DataSize.ofMegabytes(1));
        uploadFileService = mock(UploadFileService.class);
        controller = new UploadController(properties, uploadFileService);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void validPngIsStoredUnderConfiguredRoot() {
        MockMultipartFile image = new MockMultipartFile(
                "file", "cover.png", "image/png", PNG_HEADER);

        Result result = controller.uploadImage(image);

        assertThat(result.getSuccess()).isTrue();
        String publicPath = (String) result.getData();
        assertThat(publicPath).startsWith("/blogs/").endsWith(".png");
        assertThat(Files.isRegularFile(imageRoot.resolve(publicPath.substring(1)))).isTrue();
        verify(uploadFileService).registerTemporary(publicPath.substring(1), 7L);
    }

    @Test
    void mismatchedMimeAndSignatureIsRejected() {
        MockMultipartFile image = new MockMultipartFile(
                "file", "cover.png", "image/png", new byte[]{1, 2, 3, 4});

        Result result = controller.uploadImage(image);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("文件类型不匹配");
    }

    @Test
    void traversalDeleteIsRejected() {
        Result result = controller.deleteBlogImg("../../application.yaml");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("错误的文件名称");
    }

    @Test
    void managedImageCanBeDeletedIdempotently() throws Exception {
        String relative = "blogs/1/2/123e4567-e89b-12d3-a456-426614174000.png";
        Path image = imageRoot.resolve(relative);
        Files.createDirectories(image.getParent());
        Files.write(image, PNG_HEADER);
        when(uploadFileService.claimTemporaryDeletion(relative, 7L)).thenReturn(true);

        assertThat(controller.deleteBlogImg("/" + relative).getSuccess()).isTrue();
        assertThat(Files.exists(image)).isFalse();
        assertThat(controller.deleteBlogImg("/" + relative).getSuccess()).isTrue();
        verify(uploadFileService).completeTemporaryDeletion(relative, 7L);
        verify(uploadFileService).removeMissingTemporary(relative, 7L);
    }

    @Test
    void anotherUsersManagedImageCannotBeDeleted() throws Exception {
        String relative = "blogs/1/2/123e4567-e89b-12d3-a456-426614174000.png";
        Path image = imageRoot.resolve(relative);
        Files.createDirectories(image.getParent());
        Files.write(image, PNG_HEADER);
        when(uploadFileService.claimTemporaryDeletion(anyString(), eq(7L))).thenReturn(false);

        Result result = controller.deleteBlogImg("/imgs/" + relative);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("无权删除");
        assertThat(Files.exists(image)).isTrue();
    }
}
