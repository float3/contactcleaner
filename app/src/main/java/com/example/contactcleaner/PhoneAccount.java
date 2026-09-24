package com.example.contactcleaner;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

/**
 * Keeps an account named "Phone" of type "PHONE" registered.
 *
 * <p>WhatsApp's save-contact form writes raw contacts to this account. Without a matching
 * account in {@link AccountManager}, the contacts provider deletes those raw contacts the next
 * time the account list changes.
 */
final class PhoneAccount {
    static final String TYPE = "PHONE";
    static final String NAME = "Phone";

    private PhoneAccount() {
    }

    static boolean ensure(Context context) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (NAME.equals(account.name)) {
                return true;
            }
        }
        return manager.addAccountExplicitly(new Account(NAME, TYPE), null, null);
    }
}
