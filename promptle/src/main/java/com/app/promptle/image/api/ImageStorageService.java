package com.app.promptle.image.api;

/**
 * Stores generated images and retrieves them (e.g. to seed image-to-image rounds).
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

    /**
     * Fetches the raw bytes of a previously stored image, used to seed image-to-image rounds.
     *
     * @param imageUrl URL path returned by {@link #store}
     * @return raw image bytes
     */
    byte[] fetchImageBytes(String imageUrl);
}
