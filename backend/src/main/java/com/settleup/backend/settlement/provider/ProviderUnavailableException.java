package com.settleup.backend.settlement.provider;

/** The provider could not be reached or returned an error. Maps to 502; safe to retry. */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String provider, Throwable cause) {
        super("payment provider " + provider + " is unavailable, please try again", cause);
    }
}
