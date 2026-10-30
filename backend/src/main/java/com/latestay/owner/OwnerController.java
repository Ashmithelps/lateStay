package com.latestay.owner;

import com.latestay.ledger.Settlements;
import com.latestay.owner.OwnerViews.OwnerFloor;
import com.latestay.owner.OwnerViews.OwnerSummary;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hotel owner's page. Sign in with the WhatsApp link's token; after that, every endpoint works on the hotel of
 * the ls_owner cookie and takes no hotel id.
 */
@RestController
@RequestMapping("/api/owner")
class OwnerController {

    record SignInBody(@NotBlank @Size(max = 100) String token) {
    }

    record RoomsBody(@NotNull @Min(0) @Max(OwnerService.MAX_ROOMS) Integer rooms) {
    }

    record FloorBody(@NotNull @Positive Long floorPrice) {
    }

    record SignedIn(String hotelName) {
    }

    private final OwnerSessions sessions;
    private final OwnerService owners;

    OwnerController(OwnerSessions sessions, OwnerService owners) {
        this.sessions = sessions;
        this.owners = owners;
    }

    @PostMapping("/session")
    ResponseEntity<OwnerSummary> signIn(@RequestBody @Valid SignInBody body) {
        ResponseCookie cookie = sessions.signIn(body.token());
        OwnerHotel owner = sessions.check(cookie.getValue()).orElseThrow(OwnerNotSignedInException::new);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(owners.summary(owner));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> signOut(@Parameter(hidden = true) OwnerHotel owner) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessions.signOut(owner).toString()).build();
    }

    @GetMapping("/summary")
    OwnerSummary summary(@Parameter(hidden = true) OwnerHotel owner) {
        return owners.summary(owner);
    }

    @PutMapping("/rooms-tonight")
    OwnerSummary roomsTonight(@Parameter(hidden = true) OwnerHotel owner, @RequestBody @Valid RoomsBody body) {
        owners.roomsTonight(owner, body.rooms());
        return owners.summary(owner);
    }

    @PutMapping("/floor-rules/{id}")
    OwnerFloor floorPrice(@Parameter(hidden = true) OwnerHotel owner, @PathVariable UUID id,
            @RequestBody @Valid FloorBody body) {
        return owners.floorPrice(owner, id, body.floorPrice());
    }

    @GetMapping("/settlements/{id}/lines")
    List<Settlements.Line> settlementLines(@Parameter(hidden = true) OwnerHotel owner, @PathVariable UUID id) {
        return owners.settlementLines(owner, id);
    }
}
