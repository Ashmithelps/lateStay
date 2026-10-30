package com.latestay.config;

import com.latestay.booking.IllegalTransitionException;
import com.latestay.booking.InvalidBookingException;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import com.latestay.payment.PaymentGatewayException;
import com.latestay.payment.PaymentsUnavailableException;
import com.latestay.stay.InvalidStayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to problem details for every controller. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidStayException.class)
    ProblemDetail invalid(InvalidStayException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "already exists (duplicate slug or key)");
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidBookingException.class)
    ProblemDetail invalidBooking(InvalidBookingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("status", e.from().name());
        return problem;
    }

    @ExceptionHandler(com.latestay.phone.PhoneNotVerifiedException.class)
    ProblemDetail phoneNotVerified(com.latestay.phone.PhoneNotVerifiedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setProperty("code", "PHONE_NOT_VERIFIED");
        return problem;
    }

    @ExceptionHandler(com.latestay.owner.OwnerNotSignedInException.class)
    ProblemDetail ownerNotSignedIn(com.latestay.owner.OwnerNotSignedInException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setProperty("code", "OWNER_NOT_SIGNED_IN");
        return problem;
    }

    @ExceptionHandler(com.latestay.photo.PhotoRejectedException.class)
    ProblemDetail photoRejected(com.latestay.photo.PhotoRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "Photos can be up to 10 MB each. Upload fewer at once if several are large.");
        problem.setProperty("code", "TOO_LARGE");
        return problem;
    }

    @ExceptionHandler(com.latestay.share.StayShareGoneException.class)
    ProblemDetail shareGone(com.latestay.share.StayShareGoneException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler(com.latestay.promo.PromoRejectedException.class)
    ProblemDetail promoRejected(com.latestay.promo.PromoRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ProblemDetail gateway(PaymentGatewayException e) {
        log.warn("Payment gateway error: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "the payment service didn't respond; try again");
    }

    @ExceptionHandler(PaymentsUnavailableException.class)
    ProblemDetail paymentsUnavailable(PaymentsUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
