package com.latestay.admin;

import com.latestay.photo.HotelPhotos;
import com.latestay.photo.HotelPhotos.Photo;
import com.latestay.photo.PhotoRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** A hotel's photos: upload several at once, reorder, set the cover, delete. */
@RestController
@RequestMapping("/api/admin/hotels/{hotelId}/photos")
class AdminPhotoController {

    record OrderBody(@NotNull List<@NotNull Long> ids) {
    }

    /** One file that wasn't taken, with the reason to show next to it. */
    record Rejected(String fileName, String code, String message) {
    }

    record UploadResult(List<Photo> photos, List<Rejected> rejected) {
    }

    private final HotelPhotos photos;

    AdminPhotoController(HotelPhotos photos) {
        this.photos = photos;
    }

    @GetMapping
    List<Photo> list(@PathVariable UUID hotelId) {
        return photos.list(hotelId);
    }

    /** Each file is checked and stored on its own: one HEIC among five JPEGs rejects only that one. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    UploadResult upload(@PathVariable UUID hotelId, @RequestParam("files") List<MultipartFile> files) throws IOException {
        List<Rejected> rejected = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                photos.upload(hotelId, file.getBytes());
            } catch (PhotoRejectedException e) {
                rejected.add(new Rejected(file.getOriginalFilename(), e.code(), e.getMessage()));
            }
        }
        return new UploadResult(photos.list(hotelId), rejected);
    }

    @PutMapping("/order")
    List<Photo> reorder(@PathVariable UUID hotelId, @RequestBody @Valid OrderBody body) {
        return photos.reorder(hotelId, body.ids());
    }

    @PostMapping("/{photoId}/cover")
    List<Photo> cover(@PathVariable UUID hotelId, @PathVariable long photoId) {
        return photos.makeCover(hotelId, photoId);
    }

    @DeleteMapping("/{photoId}")
    List<Photo> delete(@PathVariable UUID hotelId, @PathVariable long photoId) {
        return photos.delete(hotelId, photoId);
    }
}
