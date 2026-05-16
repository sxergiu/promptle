package com.app.promptle.image.api;

/**
 * Stub interface — fully implemented in a later chunk.
 */
public interface ImageStorageService {

    /**
     * Stores image bytes and returns the URL path.
     *
     * @param gameId  the game (room) ID
     * @param imageId unique image ID
     * @param bytes   raw image bytes
     * @return URL path to access the image
     */
    String store(String gameId, String imageId, byte[] bytes);

    /**
     * Deletes all images associated with a game (room).
     *
     * @param gameId the room's UUID as a string
     */
    void deleteGame(String gameId);

    /**
     * Deletes individual images by their URL paths.
     * URLs are in the format /api/images/{gameId}/{imageId}.
     *
     * @param urls list of URL paths returned by {@link #store}
     */
    void deleteImages(java.util.List<String> urls);

    byte[] fetchImageBytes(String imageUrl);
}
