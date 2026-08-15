package com.localdeals.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class UploadFileServiceIT {

    private static final String PUBLISHED_PATH =
            "blogs/1/2/123e4567-e89b-12d3-a456-426614174001.png";
    private static final String DELETED_PATH =
            "blogs/1/2/123e4567-e89b-12d3-a456-426614174002.png";
    private static final Long OWNER_ID = 700001L;

    @Resource
    private UploadFileService uploadFileService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tb_upload_file WHERE path IN (?, ?)",
                PUBLISHED_PATH, DELETED_PATH);
    }

    @Test
    void ownershipPublishAndDeleteStateTransitionsAreEnforced() {
        uploadFileService.registerTemporary(PUBLISHED_PATH, OWNER_ID);

        assertThat(uploadFileService.validateTemporaryImages("/imgs/" + PUBLISHED_PATH, OWNER_ID))
                .containsExactly(PUBLISHED_PATH);
        assertThatThrownBy(() -> uploadFileService.validateTemporaryImages(
                "/imgs/" + PUBLISHED_PATH, OWNER_ID + 1))
                .isInstanceOf(IllegalArgumentException.class);

        uploadFileService.markPublished(Collections.singletonList(PUBLISHED_PATH), OWNER_ID, 900001L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_upload_file WHERE path = ?", String.class, PUBLISHED_PATH))
                .isEqualTo("PUBLISHED");
        assertThat(uploadFileService.claimTemporaryDeletion(PUBLISHED_PATH, OWNER_ID)).isFalse();

        uploadFileService.registerTemporary(DELETED_PATH, OWNER_ID);
        assertThat(uploadFileService.claimTemporaryDeletion(DELETED_PATH, OWNER_ID)).isTrue();
        uploadFileService.completeTemporaryDeletion(DELETED_PATH, OWNER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_upload_file WHERE path = ?", Integer.class, DELETED_PATH))
                .isZero();
    }
}
