package com.example.contactcleaner;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public class PhoneAccountService extends Service {
    private Authenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new Authenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        private final Context context;

        Authenticator(Context context) {
            super(context);
            this.context = context;
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType, String[] requiredFeatures, Bundle options) {
            PhoneAccount.ensure(context);
            Bundle result = new Bundle();
            result.putString(android.accounts.AccountManager.KEY_ACCOUNT_NAME, PhoneAccount.NAME);
            result.putString(android.accounts.AccountManager.KEY_ACCOUNT_TYPE, PhoneAccount.TYPE);
            return result;
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
            return null;
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) throws NetworkErrorException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
            Bundle result = new Bundle();
            result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false);
            return result;
        }
    }
}
