package com.latestay.owner;

/** No valid owner cookie: 401, and the page asks the owner to send "hisaab" on WhatsApp for a new link. */
public class OwnerNotSignedInException extends RuntimeException {

    public OwnerNotSignedInException() {
        super("sign in with the link LateStay sends on WhatsApp: send \"hisaab\" to get one");
    }
}
