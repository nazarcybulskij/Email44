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

import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.Loader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.app.FragmentActivity;
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
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.api.OpenKeychainIntents;
import org.sufficientlysecure.keychain.compatibility.ClipboardReflection;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.operations.results.SingletonResult;
import org.sufficientlysecure.keychain.pgp.PgpHelper;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.ui.NfcActivity;
import org.sufficientlysecure.keychain.ui.PassphraseDialogActivity;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

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

        handleActions(getActivity().getIntent());

        //sharedText = getPgpContent(sharedText);



//        data.bodyHtml="stub";
//        data.bodyText="stub";
        //Toast.makeText(getContext(), sharedText, Toast.LENGTH_SHORT).show();

        mViewController.renderMessage(data);
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

    private void handleActions( Intent intent) {
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        String type = intent.getType();

        if (extras == null) {
            extras = new Bundle();
        }

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Log.d(Constants.TAG, "ACTION_SEND");
            Log.logDebugBundle(extras, "SEND extras");

            // When sending to Keychain Decrypt via share menu
            if ("text/plain".equals(type)) {
                String sharedText = extras.getString(Intent.EXTRA_TEXT);
                sharedText = getPgpContent(sharedText);

                if (sharedText != null) {
                   // loadFragment(savedInstanceState, sharedText);
                } else {
                    Notify.showNotify(getActivity(), org.sufficientlysecure.keychain.R.string.error_invalid_data, Notify.Style.ERROR);
                }
            } else {
                Log.e(Constants.TAG, "ACTION_SEND received non-plaintext, this should not happen in this activity!");
            }
        } else if (ACTION_DECRYPT_TEXT.equals(action)) {
            Log.d(Constants.TAG, "ACTION_DECRYPT_TEXT");

            String extraText = extras.getString(EXTRA_TEXT);
            extraText = getPgpContent(extraText);

            if (extraText != null) {
               // loadFragment(savedInstanceState, extraText);
            } else {
                Notify.showNotify(getActivity(), org.sufficientlysecure.keychain.R.string.error_invalid_data, Notify.Style.ERROR);
            }
        } else if (ACTION_DECRYPT_FROM_CLIPBOARD.equals(action)) {
            Log.d(Constants.TAG, "ACTION_DECRYPT_FROM_CLIPBOARD");

            CharSequence clipboardText = ClipboardReflection.getClipboardText(getActivity());
            String text = getPgpContent(clipboardText);

            if (text != null) {
               // loadFragment(savedInstanceState, text);
            } else {
                returnInvalidResult();
            }
        } else if (ACTION_DECRYPT_TEXT.equals(action)) {
            Log.e(Constants.TAG, "Include the extra 'text' in your Intent!");
            getActivity().finish();
        }
    }
    public static final String ACTION_DECRYPT_TEXT = OpenKeychainIntents.DECRYPT_TEXT;
    public static final String EXTRA_TEXT = OpenKeychainIntents.DECRYPT_EXTRA_TEXT;
    public static final String ACTION_DECRYPT_FROM_CLIPBOARD = Constants.INTENT_PREFIX + "DECRYPT_TEXT_FROM_CLIPBOARD";


    public static final int RESULT_OK           = -1;

    private void returnInvalidResult() {
        SingletonResult result = new SingletonResult(
                SingletonResult.RESULT_ERROR, OperationResult.LogType.MSG_NO_VALID_ENC);
        Intent intent = new Intent();
        intent.putExtra(SingletonResult.EXTRA_RESULT, result);
        getActivity().setResult(RESULT_OK, intent);
        getActivity().finish();
    }





    private String getPgpContent(CharSequence input) {
        // only decrypt if clipboard content is available and a pgp message or cleartext signature
        if (!TextUtils.isEmpty(input)) {
            Log.dEscaped(Constants.TAG, "input: " + input);

            Matcher matcher = PgpHelper.PGP_MESSAGE.matcher(input);
            if (matcher.matches()) {
                String text = matcher.group(1);
                text = fixPgpMessage(text);

                Log.dEscaped(Constants.TAG, "input fixed: " + text);
                return text;
            } else {
                matcher = PgpHelper.PGP_CLEARTEXT_SIGNATURE.matcher(input);
                if (matcher.matches()) {
                    String text = matcher.group(1);
                    text = fixPgpCleartextSignature(text);

                    Log.dEscaped(Constants.TAG, "input fixed: " + text);
                    return text;
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
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


    protected void decryptStart() {
        Log.d(Constants.TAG, "decryptStart");

        // Send all information needed to service to decrypt in other thread
        Intent intent = new Intent(getActivity(), KeychainIntentService.class);

        // fill values for this action
        Bundle data = new Bundle();

        intent.setAction(KeychainIntentService.ACTION_DECRYPT_VERIFY);

        // data
        data.putInt(KeychainIntentService.TARGET, KeychainIntentService.IO_BYTES);
        data.putByteArray(KeychainIntentService.DECRYPT_CIPHERTEXT_BYTES, mCiphertex.getBytes());
        data.putString(KeychainIntentService.DECRYPT_PASSPHRASE, mPassphrase);
        data.putByteArray(KeychainIntentService.DECRYPT_NFC_DECRYPTED_SESSION_KEY, mNfcDecryptedSessionKey);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Message is received after encrypting is done in KeychainIntentService
        KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(getActivity(),
                getString(org.sufficientlysecure.keychain.R.string.progress_decrypting), ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    DecryptVerifyResult pgpResult =
                            returnData.getParcelable(DecryptVerifyResult.EXTRA_RESULT);

                    if (pgpResult.isPending()) {
                        if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE) ==
                                DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE) {
                            startPassphraseDialog(pgpResult.getKeyIdPassphraseNeeded());
                        } else if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE) ==
                                DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE) {
                            startPassphraseDialog(Constants.key.symmetric);
                        } else if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_NFC) ==
                                DecryptVerifyResult.RESULT_PENDING_NFC) {
                            startNfcDecrypt(pgpResult.getNfcSubKeyId(), pgpResult.getNfcPassphrase(), pgpResult.getNfcEncryptedSessionKey());
                        } else {
                            throw new RuntimeException("Unhandled pending result!");
                        }
                    } else if (pgpResult.success()) {

                        byte[] decryptedMessage = returnData
                                .getByteArray(KeychainIntentService.RESULT_DECRYPTED_BYTES);

                        Toast.makeText(getContext(),"decrypt",Toast.LENGTH_SHORT).show();
                        //mText.setText(new String(decryptedMessage));

                        pgpResult.createNotify(getActivity()).show();

                        // display signature result in activity
                        //boolean valid = onResult(pgpResult);


                    } else {
                        pgpResult.createNotify(getActivity()).show();
                        // TODO: show also invalid layout with different text?
                    }
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
       // Activity act =  getActivity();
        saveHandler.showProgressDialog((FragmentActivity)getActivity());

        // start service with intent
        getActivity().startService(intent);
    }

    protected void startPassphraseDialog(long subkeyId) {
        Intent intent = new Intent(getActivity(), PassphraseDialogActivity.class);
        intent.putExtra(PassphraseDialogActivity.EXTRA_SUBKEY_ID, subkeyId);
        startActivityForResult(intent, REQUEST_CODE_PASSPHRASE);
    }


    protected void startNfcDecrypt(long subKeyId, String pin, byte[] encryptedSessionKey) {
        // build PendingIntent for Yubikey NFC operations
        Intent intent = new Intent(getActivity(), NfcActivity.class);
        intent.setAction(NfcActivity.ACTION_DECRYPT_SESSION_KEY);
        intent.putExtra(NfcActivity.EXTRA_DATA, new Intent()); // not used, only relevant to OpenPgpService
        intent.putExtra(NfcActivity.EXTRA_KEY_ID, subKeyId);
        intent.putExtra(NfcActivity.EXTRA_PIN, pin);

        intent.putExtra(NfcActivity.EXTRA_NFC_ENC_SESSION_KEY, encryptedSessionKey);

        startActivityForResult(intent, REQUEST_CODE_NFC_DECRYPT);
    }
    public static final int REQUEST_CODE_PASSPHRASE = 0x00008001;
    public static final int REQUEST_CODE_NFC_DECRYPT = 0x00008002;








    //nazarko zipolino


}
