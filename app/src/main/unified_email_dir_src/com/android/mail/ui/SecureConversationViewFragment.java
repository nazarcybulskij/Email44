/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.Loader;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import com.android.mail.browse.ConversationAccountController;
import com.android.mail.browse.ConversationMessage;
import com.android.mail.browse.ConversationViewHeader;
import com.android.mail.browse.MessageCursor;
import com.android.mail.browse.MessageHeaderView;
import com.android.mail.content.ObjectCursor;
import com.android.mail.providers.Account;
import com.android.mail.providers.Address;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Message;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SecureConversationViewFragment extends AbstractConversationViewFragment
        implements SecureConversationViewControllerCallbacks {
    private static final String LOG_TAG = LogTag.getLogTag();

    private SecureConversationViewController mViewController;

    private class SecureConversationWebViewClient extends AbstractConversationWebViewClient {
        public SecureConversationWebViewClient(Account account) {
            super(account);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // Ignore unsafe calls made after a fragment is detached from an activity.
            // This method needs to, for example, get at the loader manager, which needs
            // the fragment to be added.
            if (!isAdded()) {
                LogUtils.d(LOG_TAG, "ignoring SCVF.onPageFinished, url=%s fragment=%s", url,
                        SecureConversationViewFragment.this);
                return;
            }

            if (isUserVisible()) {
                onConversationSeen();
            }

            mViewController.dismissLoadingStatus();

            final Set<String> emailAddresses = Sets.newHashSet();
            final List<Address> cacheCopy;
            synchronized (mAddressCache) {
                cacheCopy = ImmutableList.copyOf(mAddressCache.values());
            }
            for (Address addr : cacheCopy) {
                emailAddresses.add(addr.getAddress());
            }
            final ContactLoaderCallbacks callbacks = getContactInfoSource();
            callbacks.setSenders(emailAddresses);
            getLoaderManager().restartLoader(CONTACT_LOADER, Bundle.EMPTY, callbacks);
        }
    }

    /**
     * Creates a new instance of {@link ConversationViewFragment}, initialized
     * to display a conversation with other parameters inherited/copied from an
     * existing bundle, typically one created using {@link #makeBasicArgs}.
     */
    public static SecureConversationViewFragment newInstance(Bundle existingArgs,
            Conversation conversation) {
        SecureConversationViewFragment f = new SecureConversationViewFragment();
        Bundle args = new Bundle(existingArgs);
        args.putParcelable(ARG_CONVERSATION, conversation);
        f.setArguments(args);
        return f;
    }

    /**
     * Constructor needs to be public to handle orientation changes and activity
     * lifecycle events.
     */
    public SecureConversationViewFragment() {}

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        mWebViewClient = new SecureConversationWebViewClient(mAccount);
        mViewController = new SecureConversationViewController(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

        Activity activity = this.getActivity();

        // bind to service if a OpenPGP Provider is available
        if (mOpenPgpProvider != null) {
            mOpenPgpServiceConnection = new OpenPgpServiceConnection(getActivity(),
                    mOpenPgpProvider);
            mOpenPgpServiceConnection.bindToService();
        }



        return mViewController.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewController.onActivityCreated(savedInstanceState);
    }

    // Start implementations of SecureConversationViewControllerCallbacks

    @Override
    public Fragment getFragment() {
        return this;
    }

    @Override
    public AbstractConversationWebViewClient getWebViewClient() {
        return mWebViewClient;
    }

    @Override
    public void setupConversationHeaderView(ConversationViewHeader headerView) {
        headerView.setCallbacks(this, this);
        headerView.setFolders(mConversation);
        headerView.setSubject(mConversation.subject);
    }

    @Override
    public boolean isViewVisibleToUser() {
        return isUserVisible();
    }

    @Override
    public ConversationAccountController getConversationAccountController() {
        return this;
    }

    @Override
    public Map<String, Address> getAddressCache() {
        return mAddressCache;
    }

    @Override
    public void setupMessageHeaderVeiledMatcher(MessageHeaderView messageHeaderView) {
        messageHeaderView.setVeiledMatcher(
                ((ControllableActivity) getActivity()).getAccountController()
                        .getVeiledAddressMatcher());
    }

    @Override
    public void startMessageLoader() {
        getLoaderManager().initLoader(MESSAGE_LOADER, null, getMessageLoaderCallbacks());
    }

    @Override
    public String getBaseUri() {
        return mBaseUri;
    }

    @Override
    public boolean isViewOnlyMode() {
        return false;
    }

    @Override
    public Uri getAccountUri() {
        return mAccount.uri;
    }

    // End implementations of SecureConversationViewControllerCallbacks

    @Override
    protected void markUnread() {
        super.markUnread();
        // Ignore unsafe calls made after a fragment is detached from an activity
        final ControllableActivity activity = (ControllableActivity) getActivity();
        final ConversationMessage message = mViewController.getMessage();
        if (activity == null || mConversation == null || message == null) {
            LogUtils.w(LOG_TAG, "ignoring markUnread for conv=%s",
                    mConversation != null ? mConversation.id : 0);
            return;
        }
        final HashSet<Uri> uris = new HashSet<Uri>();
        uris.add(message.uri);
        activity.getConversationUpdater().markConversationMessagesUnread(mConversation, uris,
                mViewState.getConversationInfo());
    }

    @Override
    public void onAccountChanged(Account newAccount, Account oldAccount) {
        renderMessage(getMessageCursor());
    }

    @Override
    public void onConversationViewHeaderHeightChange(int newHeight) {
        // Do nothing.
    }

    @Override
    public void onUserVisibleHintChanged() {
        if (mActivity == null) {
            return;
        }
        if (isUserVisible()) {
            onConversationSeen();
        }
    }

    @Override
    protected void onMessageCursorLoadFinished(Loader<ObjectCursor<ConversationMessage>> loader,
            MessageCursor newCursor, MessageCursor oldCursor) {
        renderMessage(newCursor);
    }

    private void renderMessage(MessageCursor newCursor) {
        // ignore cursors that are still loading results
        if (newCursor == null || !newCursor.isLoaded()) {
            LogUtils.i(LOG_TAG, "CONV RENDER: existing cursor is null, rendering from scratch");
            return;
        }
        if (mActivity == null || mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }
        if (!newCursor.moveToFirst()) {
            LogUtils.e(LOG_TAG, "unable to open message cursor");
            return;
        }
        ConversationMessage data = newCursor.getMessage();

        //String sharedText = data.bodyHtml;

//        Intent clipboardDecrypt = new Intent(getActivity(), DecryptTextActivity.class);
//        clipboardDecrypt.setAction(DecryptTextActivity.ACTION_DECRYPT_FROM_CLIPBOARD);
//        startActivityForResult(clipboardDecrypt, 0);


//        mCiphertex = data.bodyText;
//        decryptStart();


        //sharedText = getPgpContent(sharedText);



       //data.bodyHtml="stub";
        // data.bodyText="stub";



        //Toast.makeText(getContext(), sharedText, Toast.LENGTH_SHORT).show();
        mData = data.bodyText;



         //decryptAndVerify(data);

         mViewController.renderMessage(data,getActivity());
    }

    // model
    public  String mCiphertex;

    @Override
    public void onConversationUpdated(Conversation conv) {
        final ConversationViewHeader headerView = mViewController.getConversationHeaderView();
        if (headerView != null) {
            headerView.onConversationUpdated(conv);
            headerView.setSubject(conv.subject);
        }
    }

    // Need this stub here
    @Override
    public boolean supportsMessageTransforms() {
        return false;
    }




    private String fixPgpMessage(String message) {
        // windows newline -> unix newline
        message = message.replaceAll("\r\n", "\n");
        // Mac OS before X newline -> unix newline
        message = message.replaceAll("\r", "\n");

        // remove whitespaces before newline
        message = message.replaceAll(" +\n", "\n");
        // only two consecutive newlines are allowed
        message = message.replaceAll("\n\n+", "\n\n");

        // replace non breakable spaces
        message = message.replaceAll("\\xa0", " ");

        return message;
    }

    /**
     * Fixing broken PGP SIGNED MESSAGE Strings coming from GMail/AOSP Mail
     */
    private String fixPgpCleartextSignature(CharSequence input) {
        if (!TextUtils.isEmpty(input)) {
            String text = input.toString();

            // windows newline -> unix newline
            text = text.replaceAll("\r\n", "\n");
            // Mac OS before X newline -> unix newline
            text = text.replaceAll("\r", "\n");

            return text;
        } else {
            return null;
        }
    }

    // State
    protected String mPassphrase;
    protected byte[] mNfcDecryptedSessionKey;



    private OpenPgpServiceConnection mOpenPgpServiceConnection;
    private OpenPgpApi mOpenPgpApi;

    private String mOpenPgpProvider = "org.sufficientlysecure.keychain";
    String mData;
    private static final int REQUEST_CODE_DECRYPT_VERIFY = 12;


    private PendingIntent mMissingKeyPI;

    private void decryptAndVerify(final Message message) {
//        this.setVisibility(View.VISIBLE);
//        mProgress.setVisibility(View.VISIBLE);
//        MessageOpenPgpView.this.setBackgroundColor(mFragment.getResources().getColor(
//                R.color.openpgp_orange));
//        mText.setText(R.string.openpgp_decrypting_verifying);

        // waiting in a new thread
        Runnable r = new Runnable() {

            @Override
            public void run() {

                // wait for service to be bound
                while (!mOpenPgpServiceConnection.isBound()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                }



                mOpenPgpApi = new OpenPgpApi(getContext(),
                            mOpenPgpServiceConnection.getService());

                    decryptVerify(new Intent());



            }
        };

        new Thread(r).start();
    }


    private void decryptVerify(Intent intent) {
        intent.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);

        //Identity identity = IdentityHelper.getRecipientIdentityFromMessage(mAccount, mMessage);
        //String accName = OpenPgpApiHelper.buildAccountName(identity);
        intent.putExtra(OpenPgpApi.EXTRA_ACCOUNT_NAME, mAccount.getEmailAddress());

        InputStream is = null;

        try {
            if (mData!=null) {
                is = new ByteArrayInputStream(mData.getBytes("UTF-8"));

            }else{
                return;
            }

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        final ByteArrayOutputStream os = new ByteArrayOutputStream();

        DecryptVerifyCallback callback = new DecryptVerifyCallback(os, REQUEST_CODE_DECRYPT_VERIFY);

        mOpenPgpApi.executeApiAsync(intent, is, os, callback);
    }


    /**
     * Called on successful decrypt/verification
     */
    private class DecryptVerifyCallback implements OpenPgpApi.IOpenPgpCallback {
        ByteArrayOutputStream os;
        int requestCode;

        private DecryptVerifyCallback(ByteArrayOutputStream os, int requestCode) {
            this.os = os;
            this.requestCode = requestCode;
        }

        @Override
        public void onReturn(Intent result) {
            switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                case OpenPgpApi.RESULT_CODE_SUCCESS: {
                    try {
                        final String output = os.toString("UTF-8");

                        OpenPgpSignatureResult sigResult = null;
                        if (result.hasExtra(OpenPgpApi.RESULT_SIGNATURE)) {
                            sigResult = result.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);
                        }
//
//                        if (K9.DEBUG)
//                            Log.d(K9.LOG_TAG, "result: " + os.toByteArray().length
//                                    + " str=" + output);

                        // missing key -> PendingIntent to get keys
                        mMissingKeyPI = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);

                        Toast.makeText(getActivity(),output,Toast.LENGTH_LONG).show();

//                        mProgress.setVisibility(View.GONE);
//                        mFragment.setMessageWithOpenPgp(output, sigResult);
                    } catch (UnsupportedEncodingException e) {
//                        Log.e(K9.LOG_TAG, "UnsupportedEncodingException", e);
                    }
                    break;
                }
                case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED: {
                    PendingIntent pi = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
                    try {
                        getActivity().startIntentSenderForResult(
                                pi.getIntentSender(),
                                requestCode, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                      // Log.e(K9.LOG_TAG, "SendIntentException", e);
                    }
                    break;
                }
                case OpenPgpApi.RESULT_CODE_ERROR: {
                    OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                    handleError(error);
                    break;
                }
            }
        }

        private void handleError(final OpenPgpError error) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    String asd = "dvxv";
//                    mProgress.setVisibility(View.GONE);
//
//                    if (K9.DEBUG) {
//                        Log.d(K9.LOG_TAG, "OpenPGP Error ID:" + error.getErrorId());
//                        Log.d(K9.LOG_TAG, "OpenPGP Error Message:" + error.getMessage());
//                    }
//
//                    mText.setText(mFragment.getString(R.string.openpgp_error) + " "
//                            + error.getMessage());
//                    MessageOpenPgpView.this.setBackgroundColor(mFragment.getResources().getColor(
//                            R.color.openpgp_red));
                }
            });
        }
    }


    //nazarko zipolino


}
