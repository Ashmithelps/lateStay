package com.latestay.review;

import com.latestay.review.ReviewDtos.MyReview;
import com.latestay.review.ReviewDtos.ReviewBody;
import com.latestay.review.ReviewDtos.ReviewState;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rating a stay from its pass (the same booking-id link the pass uses). */
@RestController
class ReviewController {

    private final Reviews reviews;

    ReviewController(Reviews reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/bookings/{id}/review")
    ReviewState state(@PathVariable UUID id) {
        return reviews.state(id);
    }

    @PostMapping("/api/bookings/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    MyReview submit(@PathVariable UUID id, @Valid @RequestBody ReviewBody body) {
        return reviews.submit(id, body);
    }
}
