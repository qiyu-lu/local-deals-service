package com.localdeals.service;

import com.localdeals.entity.UploadFile;
import com.localdeals.mapper.UploadFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadFileServiceTest {

    private static final String PATH = "blogs/1/2/123e4567-e89b-12d3-a456-426614174000.png";

    private UploadFileMapper mapper;
    private UploadFileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UploadFileMapper.class);
        service = new UploadFileService(mapper);
    }

    @Test
    void registerTemporaryPersistsOwnerAndState() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any(UploadFile.class))).thenReturn(1);

        service.registerTemporary(PATH, 7L);

        ArgumentCaptor<UploadFile> captor = ArgumentCaptor.forClass(UploadFile.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getPath()).isEqualTo(PATH);
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getStatus()).isEqualTo("TEMP");
    }

    @Test
    void validateRejectsAnotherUsersImage() {
        UploadFile upload = upload(8L, "TEMP");
        when(mapper.selectById(PATH)).thenReturn(upload);

        assertThatThrownBy(() -> service.validateTemporaryImages("/imgs/" + PATH, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权使用");
    }

    @Test
    void validatedImageCanBeMarkedPublishedForTheSameOwner() {
        when(mapper.selectById(PATH)).thenReturn(upload(7L, "TEMP"));
        when(mapper.markPublished(PATH, 7L, 99L)).thenReturn(1);

        List<String> paths = service.validateTemporaryImages("/imgs/" + PATH, 7L);
        service.markPublished(paths, 7L, 99L);

        assertThat(paths).containsExactly(PATH);
        verify(mapper).markPublished(PATH, 7L, 99L);
    }

    private UploadFile upload(Long ownerId, String status) {
        UploadFile upload = new UploadFile();
        upload.setPath(PATH);
        upload.setOwnerUserId(ownerId);
        upload.setStatus(status);
        return upload;
    }
}
