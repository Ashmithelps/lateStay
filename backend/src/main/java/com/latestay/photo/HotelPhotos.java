package com.latestay.photo;

import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A hotel's photos: upload (processed to EXIF-free WebP at 400, 800 and 1600 px), reorder, cover, delete. The
 * cover is the first photo (sort_order 0), which is what search cards and the hotel page show first.
 */
@Service
public class HotelPhotos {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_PHOTOS = 30;
    public static final List<Integer> WIDTHS = List.of(400, 800, 1600);

    private static final Logger log = LoggerFactory.getLogger(HotelPhotos.class);

    /** @param uploaded false for seed photos (a plain URL, no variants) */
    public record Photo(long id, String url, String thumbnailUrl, Integer width, Integer height, int sortOrder,
            boolean cover, boolean uploaded) {
    }

    private final JdbcClient jdbc;
    private final ImageProcessor processor;
    private final PhotoStorage storage;
    private final Clock clock;

    HotelPhotos(JdbcClient jdbc, ImageProcessor processor, PhotoStorage storage, Clock clock) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.storage = storage;
        this.clock = clock;
    }

    public List<Photo> list(UUID hotelId) {
        requireHotel(hotelId);
        return jdbc.sql("""
                        SELECT id, url, storage_key, width, height, sort_order FROM hotel_photo
                        WHERE hotel_id = :h ORDER BY sort_order""")
                .param("h", hotelId)
                .query((rs, n) -> {
                    String key = rs.getString("storage_key");
                    int order = rs.getInt("sort_order");
                    return new Photo(rs.getLong("id"), rs.getString("url"),
                            key == null ? rs.getString("url") : storage.publicUrl(key + "-400.webp"),
                            (Integer) rs.getObject("width"), (Integer) rs.getObject("height"), order, order == 0,
                            key != null);
                })
                .list();
    }

    /** Checks, processes and stores one photo, added at the end. */
    @Transactional
    public Photo upload(UUID hotelId, byte[] bytes) {
        requireHotel(hotelId);
        check(bytes);
        int count = jdbc.sql("SELECT count(*) FROM hotel_photo WHERE hotel_id = :h").param("h", hotelId)
                .query(Integer.class).single();
        if (count >= MAX_PHOTOS) {
            throw new PhotoRejectedException("TOO_MANY", "A hotel can have up to " + MAX_PHOTOS + " photos.", 409);
        }
        ImageProcessor.Processed processed = processor.process(bytes);
        String key = "hotels/" + hotelId + "/" + UUID.randomUUID();
        List<String> written = new ArrayList<>();
        try {
            for (ImageProcessor.Variant v : processed.variants()) {
                String file = key + "-" + v.width() + ".webp";
                storage.put(file, v.webp(), "image/webp");
                written.add(file);
            }
        } catch (RuntimeException e) {
            deleteQuietly(written);
            throw e;
        }
        afterRollback(written);
        Integer[] widths = processed.variants().stream().map(ImageProcessor.Variant::actualWidth)
                .toArray(Integer[]::new);
        long id = jdbc.sql("""
                        INSERT INTO hotel_photo (hotel_id, url, sort_order, storage_key, width, height, widths,
                                                 created_at)
                        VALUES (:h, :url, (SELECT COALESCE(max(sort_order) + 1, 0) FROM hotel_photo
                                           WHERE hotel_id = :h), :key, :w, :ht, :widths, :now)
                        RETURNING id""")
                .param("h", hotelId).param("url", storage.publicUrl(key + "-1600.webp")).param("key", key)
                .param("w", processed.width()).param("ht", processed.height())
                .param("widths", widths).param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .query(Long.class).single();
        return list(hotelId).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    /** A new order: every photo of the hotel, exactly once. */
    @Transactional
    public List<Photo> reorder(UUID hotelId, List<Long> ids) {
        List<Photo> current = list(hotelId);
        var expected = new HashSet<Long>();
        current.forEach(p -> expected.add(p.id()));
        if (ids.size() != current.size() || !new HashSet<>(ids).equals(expected)) {
            throw new ConflictException("the new order must list each of this hotel's photos once");
        }
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("UPDATE hotel_photo SET sort_order = :o WHERE id = :id AND hotel_id = :h")
                    .param("o", i).param("id", ids.get(i)).param("h", hotelId).update();
        }
        return list(hotelId);
    }

    /** Moves the photo to the front; the others keep their order behind it. */
    @Transactional
    public List<Photo> makeCover(UUID hotelId, long photoId) {
        List<Long> ids = new ArrayList<>(list(hotelId).stream().map(Photo::id).toList());
        if (!ids.remove(photoId)) {
            throw new NotFoundException("photo", photoId);
        }
        ids.addFirst(photoId);
        return reorder(hotelId, ids);
    }

    /** Removes the photo (its files once the transaction commits) and closes the gap in the order. */
    @Transactional
    public List<Photo> delete(UUID hotelId, long photoId) {
        String key = jdbc.sql("DELETE FROM hotel_photo WHERE id = :id AND hotel_id = :h RETURNING storage_key")
                .param("id", photoId).param("h", hotelId).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("photo", photoId));
        List<Long> rest = list(hotelId).stream().map(Photo::id).toList();
        List<Photo> photos = reorder(hotelId, rest);
        if (key != null) {
            List<String> files = WIDTHS.stream().map(w -> key + "-" + w + ".webp").toList();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteQuietly(files);
                    }
                });
            } else {
                deleteQuietly(files);
            }
        }
        return photos;
    }

    /** Before the round trip to the imaging service: size, and HEIC or not-a-photo by the first bytes. */
    static void check(byte[] bytes) {
        if (bytes.length == 0) {
            throw new PhotoRejectedException("EMPTY", "That file is empty.", 400);
        }
        if (bytes.length > MAX_BYTES) {
            throw new PhotoRejectedException("TOO_LARGE", "Photos can be up to 10 MB.", 413);
        }
        switch (sniff(bytes)) {
            case "heic" -> throw new PhotoRejectedException("HEIC", "HEIC photos aren't supported yet. On iPhone, "
                    + "choose Settings › Camera › Formats › Most Compatible, or share the photo as JPEG.", 415);
            case "jpeg", "png" -> {
            }
            default -> throw new PhotoRejectedException("UNSUPPORTED", "Only JPEG or PNG photos can be uploaded.", 415);
        }
    }

    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d
                && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "png";
        }
        if (b.length >= 12 && new String(b, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("ftyp")) {
            String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (List.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1").contains(brand)) {
                return "heic";
            }
        }
        return "other";
    }

    private void requireHotel(UUID hotelId) {
        if (jdbc.sql("SELECT count(*) FROM hotel WHERE id = :h").param("h", hotelId).query(Integer.class).single() == 0) {
            throw new NotFoundException("hotel", hotelId);
        }
    }

    /** Files written for an upload whose row never commits are removed again. */
    private void afterRollback(List<String> files) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(files);
                }
            }
        });
    }

    private void deleteQuietly(List<String> files) {
        try {
            storage.delete(files);
        } catch (RuntimeException e) {
            log.warn("Couldn't delete photo files {}: {}", files, e.getMessage());
        }
    }
}
