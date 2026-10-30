package com.latestay.push;

/** Without Firebase credentials nothing is sent; queued messages become NOT_SENT. */
class DisabledPushGateway implements PushGateway {

    @Override
    public boolean canSend() {
        return false;
    }

    @Override
    public Result send(String token, PushPayload payload) {
        return Result.FAILED;
    }
}
