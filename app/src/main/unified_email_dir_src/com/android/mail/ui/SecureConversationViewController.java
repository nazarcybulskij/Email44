/*
 * Copyright (C) 2013 Google Inc.
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
import android.app.FragmentManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.TextView;

import com.android.email_ee.R;
import com.android.mail.FormattedDateBuilder;
import com.android.mail.browse.BorderView;
import com.android.mail.browse.ConversationMessage;
import com.android.mail.browse.ConversationViewAdapter;
import com.android.mail.browse.ConversationViewAdapter.MessageHeaderItem;
import com.android.mail.browse.ConversationViewHeader;
import com.android.mail.browse.MessageFooterView;
import com.android.mail.browse.MessageHeaderView;
import com.android.mail.browse.MessageScrollView;
import com.android.mail.browse.MessageWebView;
import com.android.mail.providers.Message;
import com.android.mail.utils.ConversationViewUtils;

import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import de.greenrobot.event.EventBus;
import ua.indeema.nazar.MessageEvent;

/**
 * Controller to do most of the heavy lifting for {@link SecureConversationViewFragment}
 * and {@link com.android.mail.browse.EmlMessageViewFragment}. Currently that work is
 * pretty much the rendering logic.
 */
public class SecureConversationViewController implements
        MessageHeaderView.MessageHeaderViewCallbacks {
    private static final String BEGIN_HTML =
            "<body style=\"margin: 0 %spx;\"><div style=\"margin: 16px 0; font-size: 80%%\">";
    private static final String END_HTML = "</div></body>";

    private final SecureConversationViewControllerCallbacks mCallbacks;

    private MessageWebView mWebView;
    private ConversationViewHeader mConversationHeaderView;
    private MessageHeaderView mMessageHeaderView;
    private MessageFooterView mMessageFooterView;
    private ConversationMessage mMessage;
    private MessageScrollView mScrollView;
    private Button mDecrypt;
    private TextView mText = null;

    private ConversationViewProgressController mProgressController;
    private FormattedDateBuilder mDateBuilder;

    private int mSideMarginInWebPx;

    public SecureConversationViewController(SecureConversationViewControllerCallbacks callbacks) {
        mCallbacks = callbacks;
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.secure_conversation_view, container, false);
        mScrollView = (MessageScrollView) rootView.findViewById(R.id.scroll_view);
        mConversationHeaderView = (ConversationViewHeader) rootView.findViewById(R.id.conv_header);
        mMessageHeaderView = (MessageHeaderView) rootView.findViewById(R.id.message_header);
        mMessageFooterView = (MessageFooterView) rootView.findViewById(R.id.message_footer);
        mText = (TextView)rootView.findViewById(R.id.openpgp_text);
        mText.setVisibility(View.VISIBLE);



        // Add color backgrounds to the header and footer.
        // Otherwise the backgrounds are grey. They can't
        // be set in xml because that would add more overdraw
        // in ConversationViewFragment.
        final int color = rootView.getResources().getColor(
                R.color.message_header_background_color);
        mMessageHeaderView.setBackgroundColor(color);
        mMessageFooterView.setBackgroundColor(color);

        ((BorderView) rootView.findViewById(R.id.top_border)).disableCardBottomBorder();
        ((BorderView) rootView.findViewById(R.id.bottom_border)).disableCardTopBorder();

        mProgressController = new ConversationViewProgressController(
                mCallbacks.getFragment(), mCallbacks.getHandler());
        mProgressController.instantiateProgressIndicators(rootView);
        mWebView = (MessageWebView) rootView.findViewById(R.id.webview);
        mWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mWebView.setWebViewClient(mCallbacks.getWebViewClient());
        mWebView.setFocusable(false);
        final WebSettings settings = mWebView.getSettings();

        settings.setJavaScriptEnabled(false);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        ConversationViewUtils.setTextZoom(mCallbacks.getFragment().getResources(), settings);

        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);



        mScrollView.setInnerScrollableView(mWebView);



        return rootView;
    }

    public void onActivityCreated(Bundle savedInstanceState) {
        mCallbacks.setupConversationHeaderView(mConversationHeaderView);

        final Fragment fragment = mCallbacks.getFragment();

        mDateBuilder = new FormattedDateBuilder(fragment.getActivity());
        mMessageHeaderView.initialize(
                mCallbacks.getConversationAccountController(), mCallbacks.getAddressCache());
        mMessageHeaderView.setExpandMode(MessageHeaderView.POPUP_MODE);
        mMessageHeaderView.setContactInfoSource(mCallbacks.getContactInfoSource());
        mMessageHeaderView.setCallbacks(this);
        mMessageHeaderView.setExpandable(false);
        mMessageHeaderView.setViewOnlyMode(mCallbacks.isViewOnlyMode());

        mCallbacks.setupMessageHeaderVeiledMatcher(mMessageHeaderView);

        mMessageFooterView.initialize(fragment.getLoaderManager(), fragment.getFragmentManager());

        mCallbacks.startMessageLoader();

        mProgressController.showLoadingStatus(mCallbacks.isViewVisibleToUser());

        final Resources r = mCallbacks.getFragment().getResources();
        mSideMarginInWebPx = (int) (r.getDimensionPixelOffset(
                R.dimen.conversation_message_content_margin_side) / r.getDisplayMetrics().density);
    }


    //Nazarko Zipolino    add RenderMessagewithContext()

    /**
     * Populate the adapter with overlay views (message headers, super-collapsed
     * blocks, a conversation header), and return an HTML document with spacer
     * divs inserted for all overlays.
     */
    public void renderMessage(ConversationMessage message) {
        mMessage = message;
        mWebView.getSettings().setBlockNetworkImage(!mMessage.alwaysShowImages);

        // Add formatting to message body
        // At this point, only adds margins.
        StringBuilder dataBuilder = new StringBuilder(
                String.format(BEGIN_HTML, mSideMarginInWebPx));

        String body = mMessage.getBodyAsHtml();
        dataBuilder.append(body);

        dataBuilder.append(END_HTML);

        mWebView.loadDataWithBaseURL(mCallbacks.getBaseUri(), dataBuilder.toString(),
                "text/html", "utf-8", null);
        final MessageHeaderItem item = ConversationViewAdapter.newMessageHeaderItem(
                null, mDateBuilder, mMessage, true, mMessage.alwaysShowImages);
        // Clear out the old info from the header before (re)binding
        mMessageHeaderView.unbind();
        mMessageHeaderView.bind(item, false);
        if (mMessage.hasAttachments) {
            mMessageFooterView.setVisibility(View.VISIBLE);
            mMessageFooterView.bind(item, mCallbacks.getAccountUri(), false);
        }

         if (mCallbacks.isViewVisibleToUser() ){
             EventBus.getDefault().post(new MessageEvent(message,"Nazar"));
         }




    }


    private Context mContext;

    public void renderMessage(ConversationMessage message ,Context context) {

        if (mMessage==null)
            return;

        mContext=context;


        StringBuilder dataBuilder = new StringBuilder(
                String.format(BEGIN_HTML, mSideMarginInWebPx));

        String body = mMessage.getBodyAsHtml();
        dataBuilder.append(body);

        dataBuilder.append(END_HTML);

        mWebView.loadDataWithBaseURL(mCallbacks.getBaseUri(), dataBuilder.toString(),
                "text/html", "utf-8", null);


        if (mOpenPgpProvider != null) {
            mOpenPgpServiceConnection = new OpenPgpServiceConnection(mContext,
                    mOpenPgpProvider);
            mOpenPgpServiceConnection.bindToService();
        }

        mData=mMessage.bodyText;


        decryptAndVerify(mMessage);







    }
 //-----------------------------------------------------------

    public OpenPgpServiceConnection mOpenPgpServiceConnection;
    private OpenPgpApi mOpenPgpApi;

    private String mOpenPgpProvider = "org.sufficientlysecure.keychain";
    String mData;
    private static final int REQUEST_CODE_DECRYPT_VERIFY = 12;
    private PendingIntent mMissingKeyPI;


    private void decryptAndVerify(final Message message) {

        mText.setText(R.string.openpgp_decrypting_verifying);
        mText.setBackgroundColor(Color.YELLOW);
        mText.setTextColor(Color.BLACK);
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

//
                if (mOpenPgpApi!=null){
                    mOpenPgpApi=null;
                    return;
                }
                mOpenPgpApi = new OpenPgpApi(mContext,
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

        String tempEmail = getMessage().getTo();
        if (tempEmail!=null) {
            String email = null;

            if (tempEmail.indexOf("<")==-1){
                email = tempEmail;
            }else{
                email = tempEmail.substring(tempEmail.indexOf("<") + 1, tempEmail.indexOf(">"));
            }
            intent.putExtra(OpenPgpApi.EXTRA_ACCOUNT_NAME,email);
        }

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

                        //Toast.makeText(mContext, output, Toast.LENGTH_SHORT).show();
                        mMessage.bodyText = output;
                        renderMessage(mMessage);
                        mText.setText(R.string.openpgp_successful_decryption);
                        mText.setBackgroundColor(Color.GREEN);
                        mText.setTextColor(Color.BLACK);



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
                        Activity activity= (Activity)mContext;
                        activity.startIntentSenderForResult(
                                pi.getIntentSender(),
                                requestCode, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        // Log.e(K9.LOG_TAG, "SendIntentException", e);
                    }
                    break;
                }
                case OpenPgpApi.RESULT_CODE_ERROR: {
                    OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                    mText.setBackgroundColor(Color.RED);
                    mText.setTextColor(Color.BLACK);
                    handleError(error);
                    break;
                }
            }
        }

        private void handleError(final OpenPgpError error) {
            final Activity activity = (Activity)mContext;
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    if (mText.getText().toString().equals(R.string.openpgp_successful_decryption)) {

                        mText.setText(activity.getString(R.string.openpgp_error) + " "
                                + error.getMessage());
                    }
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


//--------------------------------------------------Nazarko Zipolino

    public ConversationMessage getMessage() {
        return mMessage;
    }

    public ConversationViewHeader getConversationHeaderView() {
        return mConversationHeaderView;
    }

    public void dismissLoadingStatus() {
        mProgressController.dismissLoadingStatus();
    }

    public void setSubject(String subject) {
        mConversationHeaderView.setSubject(subject);
    }

    // Start MessageHeaderViewCallbacks implementations

    @Override
    public void setMessageSpacerHeight(MessageHeaderItem item, int newSpacerHeight) {
        // Do nothing.
    }

    @Override
    public void setMessageExpanded(MessageHeaderItem item, int newSpacerHeight,
            int topBorderHeight, int bottomBorderHeight) {
        // Do nothing.
    }

    @Override
    public void setMessageDetailsExpanded(MessageHeaderItem i, boolean expanded, int heightBefore) {
        // Do nothing.
    }

    @Override
    public void showExternalResources(final Message msg) {
        mWebView.getSettings().setBlockNetworkImage(false);
    }

    @Override
    public void showExternalResources(final String rawSenderAddress) {
        mWebView.getSettings().setBlockNetworkImage(false);
    }

    @Override
    public boolean supportsMessageTransforms() {
        return false;
    }

    @Override
    public String getMessageTransforms(final Message msg) {
        return null;
    }

    @Override
    public FragmentManager getFragmentManager() {
        return mCallbacks.getFragment().getFragmentManager();
    }








    // End MessageHeaderViewCallbacks implementations
}
