package com.latestay.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.booking.BookingItSupport;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.hotel.HotelRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** Admin photo uploads with fake processing and storage (EXIF stripping itself is tested in imaging/). */
class HotelPhotosIT extends BookingItSupport {

    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13};
    static final byte[] HEIC = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};

    @Autowired
    FakePhotos.Storage storage;
    @Autowired
    FakePhotos.Processor processor;
    @Autowired
    ObjectMapper json;
    @Autowired
    HotelRepository hotelRepository;

    /** A hotel without the fixture's seed photo. */
    Hotel hotel() {
        Hotel hotel = hotels.hotel("Photo Inn", 35.0 + Math.random(), 79.4, List.of(), HotelPolicies.NONE, true);
        jdbc.sql("DELETE FROM hotel_photo WHERE hotel_id = :h").param("h", hotel.id()).update();
        return hotel;
    }

    JsonNode upload(Hotel hotel, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/admin/hotels/{id}/photos", hotel.id());
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return json.readTree(mvc.perform(request.with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", bytes);
    }

    List<Long> ids(JsonNode photos) {
        List<Long> ids = new ArrayList<>();
        photos.forEach(p -> ids.add(p.path("id").asLong()));
        return ids;
    }

    @Test
    void severalPhotosUploadAsWebpVariantsAndAHeicIsRejectedOnItsOwn() throws Exception {
        Hotel hotel = hotel();
        JsonNode result = upload(hotel, file("front.jpg", JPEG), file("iphone.heic", HEIC), file("room.png", PNG));

        assertThat(result.path("photos")).hasSize(2);
        assertThat(result.path("rejected")).singleElement().satisfies(r -> {
            assertThat(r.path("fileName").asText()).isEqualTo("iphone.heic");
            assertThat(r.path("code").asText()).isEqualTo("HEIC");
            assertThat(r.path("message").asText()).contains("Most Compatible");
        });
        JsonNode first = result.path("photos").get(0);
        assertThat(first.path("cover").asBoolean()).isTrue();
        assertThat(first.path("url").asText()).matches("https://photos.test/hotels/" + hotel.id() + "/[0-9a-f-]{36}-1600\\.webp");
        assertThat(first.path("thumbnailUrl").asText()).endsWith("-400.webp");
        assertThat(first.path("width").asInt()).isEqualTo(2000);

        String prefix = first.path("url").asText().replace("https://photos.test/", "").replace("-1600.webp", "");
        assertThat(storage.files).containsKeys(prefix + "-400.webp", prefix + "-800.webp", prefix + "-1600.webp");
        // The hotel page and search use the uploaded photos, cover first.
        assertThat(hotelRepository.find(hotel.id()).orElseThrow().photos()).first().isEqualTo(first.path("url").asText());
    }

    @Test
    void notAPhotoOrTooLargeIsRejectedBeforeProcessing() throws Exception {
        Hotel hotel = hotel();
        int before = processor.calls.get();
        byte[] big = new byte[HotelPhotos.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        JsonNode result = upload(hotel, file("notes.pdf", "%PDF-1.7".getBytes()), file("huge.jpg", big));
        assertThat(result.path("photos")).isEmpty();
        assertThat(result.path("rejected").findValuesAsText("code")).containsExactly("UNSUPPORTED", "TOO_LARGE");
        assertThat(processor.calls.get()).isEqualTo(before);
    }

    @Test
    void reorderSetCoverAndDelete() throws Exception {
        Hotel hotel = hotel();
        List<Long> ids = ids(upload(hotel, file("a.jpg", JPEG), file("b.jpg", JPEG), file("c.jpg", JPEG)).path("photos"));

        mvc.perform(put("/api/admin/hotels/{id}/photos/order", hotel.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [" + ids.get(2) + "," + ids.get(0) + "," + ids.get(1) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$[0].cover").value(true));

        // Not every photo exactly once: refused.
        mvc.perform(put("/api/admin/hotels/{id}/photos/order", hotel.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\": [" + ids.get(0) + "]}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/hotels/{id}/photos/{p}/cover", hotel.id(), ids.get(1))
                        .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(jsonPath("$[0].id").value(ids.get(1)))
                .andExpect(jsonPath("$[1].id").value(ids.get(2)))
                .andExpect(jsonPath("$[2].id").value(ids.get(0)));

        String url = jdbc.sql("SELECT storage_key FROM hotel_photo WHERE id = :id").param("id", ids.get(1))
                .query(String.class).single();
        mvc.perform(delete("/api/admin/hotels/{id}/photos/{p}", hotel.id(), ids.get(1))
                        .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].sortOrder").value(1));
        assertThat(storage.files.keySet()).noneMatch(k -> k.startsWith(url));
    }

    @Test
    void anotherHotelsPhotoCannotBeTouched() throws Exception {
        Hotel mine = hotel();
        Hotel theirs = hotel();
        long theirPhoto = ids(upload(theirs, file("a.jpg", JPEG)).path("photos")).getFirst();
        mvc.perform(delete("/api/admin/hotels/{id}/photos/{p}", mine.id(), theirPhoto)
                .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/hotels/{id}/photos/{p}/cover", mine.id(), theirPhoto)
                .with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/hotels/{id}/photos", theirs.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void savingTheHotelFormWithoutPhotosKeepsThem() throws Exception {
        Hotel hotel = hotel();
        upload(hotel, file("a.jpg", JPEG));
        mvc.perform(put("/api/admin/hotels/{id}", hotel.id()).with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Photo Inn renamed", "slug": "%s", "town": "Manali", "address": "1 Test Road",
                                 "lat": %s, "lng": 79.4, "whatsappNumber": "%s"}"""
                                .formatted(hotel.slug(), hotel.lat(), hotel.whatsappNumber())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Photo Inn renamed"))
                .andExpect(jsonPath("$.photos.length()").value(1));
    }

    @Test
    void magicBytesDecideTheFormat() {
        assertThat(HotelPhotos.sniff(JPEG)).isEqualTo("jpeg");
        assertThat(HotelPhotos.sniff(PNG)).isEqualTo("png");
        assertThat(HotelPhotos.sniff(HEIC)).isEqualTo("heic");
        assertThat(HotelPhotos.sniff("GIF89a".getBytes())).isEqualTo("other");
    }
}
