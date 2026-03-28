package com.app.promptle.image.storage;

import com.app.promptle.image.api.ImageStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stub — not yet implemented. Throws UnsupportedOperationException.
 * Active when image.storage.provider=s3.
 */
@Service
@ConditionalOnProperty(name = "image.storage.provider", havingValue = "s3")
public class S3ImageStorageService implements ImageStorageService {

    public S3ImageStorageService() {}

    @Override
    public String store(String gameId, String imageId, byte[] bytes) {
        throw new UnsupportedOperationException("S3 storage not yet implemented");
    }

    @Override
    public void deleteGame(String gameId) {
        throw new UnsupportedOperationException("S3 storage not yet implemented");
    }

    @Override
    public void deleteImages(List<String> urls) {
        throw new UnsupportedOperationException("S3 storage not yet implemented");
    }
}
