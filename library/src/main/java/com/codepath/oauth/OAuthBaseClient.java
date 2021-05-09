package com.codepath.oauth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.github.scribejava.core.builder.api.BaseApi;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.github.scribejava.core.model.OAuth1RequestToken;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthConstants;
import com.github.scribejava.core.model.Token;

import java.util.HashMap;

public abstract class OAuthBaseClient {
    protected String baseUrl;
    protected Context context;
    protected OAuthTokenClient tokenClient;
    protected OAuthAsyncHttpClient client;
    protected SharedPreferences prefs;
    protected SharedPreferences.Editor editor;
    protected OAuthAccessHandler accessHandler;
    protected String callbackUrl;
    protected int requestIntentFlags = -1;

    private static final String OAUTH1_REQUEST_TOKEN = "request_token";
    private static final String OAUTH1_REQUEST_TOKEN_SECRET = "request_token_secret";
    private static final String OAUTH1_VERSION = "1.0";
    private static final String OAUTH2_VERSION = "2.0";

    protected static HashMap<Class<? extends OAuthBaseClient>, OAuthBaseClient> instances =
            new HashMap<Class<? extends OAuthBaseClient>, OAuthBaseClient>();

    public static OAuthBaseClient getInstance(Class<? extends OAuthBaseClient> klass, Context context) {
        OAuthBaseClient instance = instances.get(klass);
        if (instance == null) {
            try {
                instance = (OAuthBaseClient) klass.getConstructor(Context.class).newInstance(context);
                instances.put(klass, instance);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return instance;
    }

    public OAuthBaseClient(Context c, final BaseApi apiInstance, String consumerUrl, final String consumerKey, final String consumerSecret, @Nullable String scope, String callbackUrl) {
        this.baseUrl = consumerUrl;
        this.callbackUrl = callbackUrl;
        tokenClient = new OAuthTokenClient(apiInstance, consumerKey,
                consumerSecret, callbackUrl, scope, new OAuthTokenClient.OAuthTokenHandler() {

            // Store request token and launch the authorization URL in the browser
            @Override
            public void onReceivedRequestToken(Token requestToken, String authorizeUrl, String oAuthVersion) {
                if (requestToken != null) {
                    if (oAuthVersion == OAUTH1_VERSION) {  // store for OAuth1.0a
                        OAuth1RequestToken oAuth1RequestToken = (OAuth1RequestToken) requestToken;
                        editor.putString(OAUTH1_REQUEST_TOKEN, oAuth1RequestToken.getToken());
                        editor.putString(OAUTH1_REQUEST_TOKEN_SECRET, oAuth1RequestToken.getTokenSecret());
                        editor.putInt(OAuthConstants.VERSION, 1);
                        editor.commit();
                    }
                }
                // Launch the authorization URL in the browser
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl));
                if (requestIntentFlags != -1) {
                    intent.setFlags(requestIntentFlags);
                }
                OAuthBaseClient.this.context.startActivity(intent);
            }

            // Store the access token in preferences, set the token in the tokenClient and fire the success callback
            @Override
            public void onReceivedAccessToken(Token accessToken, String oAuthVersion) {

                if (oAuthVersion == OAUTH1_VERSION) {
                    OAuth1AccessToken oAuth1AccessToken = (OAuth1AccessToken) accessToken;

                    tokenClient.setAccessToken(accessToken);
                    instantiateClient(consumerKey, consumerSecret, oAuth1AccessToken);
                    editor.putString(OAuthConstants.TOKEN, oAuth1AccessToken.getToken());
                    editor.putString(OAuthConstants.TOKEN_SECRET, oAuth1AccessToken.getTokenSecret());
                    editor.putInt(OAuthConstants.VERSION, 1);
                    editor.commit();
                } else if (oAuthVersion == OAUTH2_VERSION) {
                    OAuth2AccessToken oAuth2AccessToken = (OAuth2AccessToken) accessToken;
                    instantiateClient(consumerKey, consumerSecret, oAuth2AccessToken);
                    tokenClient.setAccessToken(accessToken);
                    editor.putString(OAuthConstants.TOKEN, oAuth2AccessToken.getAccessToken());
                    editor.putString(OAuthConstants.SCOPE, oAuth2AccessToken.getScope());
                    editor.putString(OAuthConstants.REFRESH_TOKEN, oAuth2AccessToken.getRefreshToken());
                    editor.putInt(OAuthConstants.VERSION, 2);
                    editor.commit();

                }
                accessHandler.onLoginSuccess();
            }

            @Override
            public void onFailure(Exception e) {
                if (accessHandler != null) {
                    accessHandler.onLoginFailure(e);
                }
            }

        });

        this.context = c;
        // Store preferences namespaced by the class and consumer key used
        this.prefs = this.context.getSharedPreferences("OAuth_" + apiInstance.getClass().getSimpleName() + "_" + consumerKey, 0);
        this.editor = this.prefs.edit();
        // Set access token in the tokenClient if already stored in preferences
        Token accessToken = this.checkAccessToken();
        if (accessToken != null) {
            tokenClient.setAccessToken(accessToken);
            instantiateClient(consumerKey, consumerSecret, accessToken);
        }
    }

    public void instantiateClient(String consumerKey, String consumerSecret, Token token) {

        if (token instanceof OAuth1AccessToken) {
            client = OAuthAsyncHttpClient.create(consumerKey, consumerSecret, (OAuth1AccessToken) (token));
        } else if (token instanceof OAuth2AccessToken) {
            client = OAuthAsyncHttpClient.create((OAuth2AccessToken) token);
        } else {
            throw new IllegalStateException("unrecognized token type" + token);
        }

    }

    // Fetches a request token and retrieve and authorization url
    // Should open a browser in onReceivedRequestToken once the url has been received
    public void connect() {
        tokenClient.fetchRequestToken();
    }

    // Retrieves access token given authorization url
    public void authorize(Uri uri, OAuthAccessHandler handler) {
        this.accessHandler = handler;
        if (checkAccessToken() == null && uri != null) {
            // TODO: check UriServiceCallback with intent:// scheme
            tokenClient.fetchAccessToken(getOAuth1RequestToken(), uri);

        } else if (checkAccessToken() != null) { // already have access token
            this.accessHandler.onLoginSuccess();
        }
    }

    // Return access token if the token exists in preferences
    public Token checkAccessToken() {
        int oAuthVersion = prefs.getInt(OAuthConstants.VERSION, 0);

        if (oAuthVersion == 1 && prefs.contains(OAuthConstants.TOKEN) && prefs.contains(OAuthConstants.TOKEN_SECRET)) {
            return new OAuth1AccessToken(prefs.getString(OAuthConstants.TOKEN, ""),
                    prefs.getString(OAuthConstants.TOKEN_SECRET, ""));
        } else if (oAuthVersion == 2 && prefs.contains(OAuthConstants.TOKEN)) {
            return new OAuth2AccessToken(prefs.getString(OAuthConstants.TOKEN, ""));
        }
        return null;
    }

    protected OAuthTokenClient getTokenClient() {
        return tokenClient;
    }

    // Returns the request token stored during the request token phase (OAuth1 only)
    protected @Nullable
    Token getOAuth1RequestToken() {
        int oAuthVersion = prefs.getInt(OAuthConstants.VERSION, 0);

        if (oAuthVersion == 1) {
            return new OAuth1RequestToken(prefs.getString(OAUTH1_REQUEST_TOKEN, ""),
                    prefs.getString(OAUTH1_REQUEST_TOKEN_SECRET, ""));
        }
        return null;
    }

    // Assigns the base url for the API
    protected void setBaseUrl(String url) {
        this.baseUrl = url;
    }

    // Returns the full ApiUrl
    protected String getApiUrl(String path) {
        return this.baseUrl + "/" + path;
    }

    // Removes the access tokens (for signing out)
    public void clearAccessToken() {
        tokenClient.setAccessToken(null);
        editor.remove(OAuthConstants.TOKEN);
        editor.remove(OAuthConstants.TOKEN_SECRET);
        editor.remove(OAuthConstants.REFRESH_TOKEN);
        editor.remove(OAuthConstants.SCOPE);
        editor.commit();
    }

    // Returns true if the tokenClient is authenticated; false otherwise.
    public boolean isAuthenticated() {
        return tokenClient.getAccessToken() != null;
    }

    // Sets the flags used when launching browser to authenticate through OAuth
    public void setRequestIntentFlags(int flags) {
        this.requestIntentFlags = flags;
    }

    // Defines the handler events for the OAuth flow
    public static interface OAuthAccessHandler {
        public void onLoginSuccess();

        public void onLoginFailure(Exception e);
    }
}
