package com.app.promptle.image.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class ImageController {

    private final String basePath;

    public ImageController(@Value("${image.storage.local.path:/tmp/promptle}") String basePath) {
        this.basePath = basePath;
    }

    @GetMapping("/api/images/{gameId}/{imageId}")
    public ResponseEntity<Resource> getImage(@PathVariable String gameId,
                                             @PathVariable String imageId) {
        Path path = Paths.get(basePath, gameId, imageId + ".png");
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }
}
