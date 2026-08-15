package com.localdeals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "local-deals.upload")
public class UploadProperties {
    private Path imageRoot = Paths.get("./frontend/user/imgs");
    private DataSize maxImageSize = DataSize.ofMegabytes(5);

    public Path getImageRoot() {
        return imageRoot;
    }

    public void setImageRoot(Path imageRoot) {
        this.imageRoot = imageRoot;
    }

    public DataSize getMaxImageSize() {
        return maxImageSize;
    }

    public void setMaxImageSize(DataSize maxImageSize) {
        this.maxImageSize = maxImageSize;
    }
}
