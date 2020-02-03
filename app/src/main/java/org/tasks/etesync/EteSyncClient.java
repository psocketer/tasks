package org.tasks.etesync;

import static com.google.common.collect.Lists.transform;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import at.bitfire.cert4android.CustomCertManager;
import at.bitfire.cert4android.CustomCertManager.CustomHostnameVerifier;
import com.etesync.journalmanager.Crypto;
import com.etesync.journalmanager.Crypto.CryptoManager;
import com.etesync.journalmanager.Exceptions;
import com.etesync.journalmanager.Exceptions.HttpException;
import com.etesync.journalmanager.Exceptions.IntegrityException;
import com.etesync.journalmanager.Exceptions.VersionTooNewException;
import com.etesync.journalmanager.JournalAuthenticator;
import com.etesync.journalmanager.JournalEntryManager;
import com.etesync.journalmanager.JournalEntryManager.Entry;
import com.etesync.journalmanager.JournalManager;
import com.etesync.journalmanager.JournalManager.Journal;
import com.etesync.journalmanager.UserInfoManager;
import com.etesync.journalmanager.UserInfoManager.UserInfo;
import com.etesync.journalmanager.model.CollectionInfo;
import com.etesync.journalmanager.model.SyncEntry;
import com.etesync.journalmanager.util.TokenAuthenticator;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.internal.tls.OkHostnameVerifier;
import org.tasks.DebugNetworkInterceptor;
import org.tasks.caldav.MemoryCookieStore;
import org.tasks.data.CaldavAccount;
import org.tasks.injection.ForApplication;
import org.tasks.preferences.Preferences;
import org.tasks.security.Encryption;
import timber.log.Timber;

public class EteSyncClient {

  private static final int MAX_FETCH = 50;

  private final Encryption encryption;
  private final Preferences preferences;
  private final DebugNetworkInterceptor interceptor;
  private final String username;
  private final String encryptionPassword;
  private final OkHttpClient httpClient;
  private final HttpUrl httpUrl;
  private final Context context;
  private final JournalManager journalManager;
  private boolean foreground;

  @Inject
  public EteSyncClient(
      @ForApplication Context context,
      Encryption encryption,
      Preferences preferences,
      DebugNetworkInterceptor interceptor) {
    this.context = context;
    this.encryption = encryption;
    this.preferences = preferences;
    this.interceptor = interceptor;
    username = null;
    encryptionPassword = null;
    httpClient = null;
    httpUrl = null;
    journalManager = null;
  }

  private EteSyncClient(
      Context context,
      Encryption encryption,
      Preferences preferences,
      DebugNetworkInterceptor interceptor,
      String url,
      String username,
      String encryptionPassword,
      String token,
      boolean foreground)
      throws NoSuchAlgorithmException, KeyManagementException {
    this.context = context;
    this.encryption = encryption;
    this.preferences = preferences;
    this.interceptor = interceptor;
    this.username = username;
    this.encryptionPassword = encryptionPassword;

    CustomCertManager customCertManager = new CustomCertManager(context);
    customCertManager.setAppInForeground(foreground);
    CustomHostnameVerifier hostnameVerifier =
        customCertManager.hostnameVerifier(OkHostnameVerifier.INSTANCE);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] {customCertManager}, null);

    Builder builder =
        new OkHttpClient()
            .newBuilder()
            .addNetworkInterceptor(new TokenAuthenticator(null, token))
            .cookieJar(new MemoryCookieStore())
            .followRedirects(false)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.getSocketFactory(), customCertManager)
            .hostnameVerifier(hostnameVerifier)
            .readTimeout(30, TimeUnit.SECONDS);
    if (preferences.isFlipperEnabled()) {
      interceptor.add(builder);
    }
    httpClient = builder.build();
    httpUrl = HttpUrl.parse(url);
    journalManager = new JournalManager(httpClient, httpUrl);
  }

  public EteSyncClient forAccount(CaldavAccount account)
      throws NoSuchAlgorithmException, KeyManagementException {
    return forUrl(
        account.getUrl(),
        account.getUsername(),
        account.getEncryptionPassword(encryption),
        account.getPassword(encryption));
  }

  public EteSyncClient forUrl(String url, String username, String encryptionPassword, String token)
      throws KeyManagementException, NoSuchAlgorithmException {
    return new EteSyncClient(
        context,
        encryption,
        preferences,
        interceptor,
        url,
        username,
        encryptionPassword,
        token,
        foreground);
  }

  public Pair<String, String> getKeyAndToken(String password)
      throws IOException, Exceptions.HttpException, VersionTooNewException, IntegrityException {
    JournalAuthenticator journalAuthenticator = new JournalAuthenticator(httpClient, httpUrl);
    String token = journalAuthenticator.getAuthToken(username, password);
    UserInfoManager userInfoManager = new UserInfoManager(httpClient, httpUrl);
    UserInfo userInfo = userInfoManager.fetch(username);
    String key = Crypto.deriveKey(username, encryptionPassword);
    CryptoManager cryptoManager = new CryptoManager(userInfo.getVersion(), key, "userInfo");
    userInfo.verify(cryptoManager);
    return Pair.create(token, key);
  }

  CryptoManager getCrypto(Journal journal)
      throws VersionTooNewException, IntegrityException {
    return new CryptoManager(journal.getVersion(), encryptionPassword, journal.getUid());
  }

  private @Nullable CollectionInfo convertJournalToCollection(Journal journal) {
    try {
      CryptoManager cryptoManager = getCrypto(journal);
      journal.verify(cryptoManager);
      CollectionInfo collection =
          CollectionInfo.Companion.fromJson(journal.getContent(cryptoManager));
      collection.updateFromJournal(journal);
      return collection;
    } catch (IntegrityException | VersionTooNewException e) {
      Timber.e(e);
      return null;
    }
  }

  public Map<Journal, CollectionInfo> getCalendars() throws Exceptions.HttpException {
    Map<Journal, CollectionInfo> result = new HashMap<>();
    for (Journal journal : journalManager.list()) {
      CollectionInfo collection = convertJournalToCollection(journal);
      if (collection != null && collection.getType().equals("TASKS")) {
        result.put(journal, collection);
      }
    }
    return result;
  }

  List<Pair<Entry, SyncEntry>> getSyncEntries(Journal journal, @Nullable String ctag)
      throws IntegrityException, Exceptions.HttpException, VersionTooNewException {
    JournalEntryManager journalEntryManager =
        new JournalEntryManager(httpClient, httpUrl, journal.getUid());
    CryptoManager crypto = getCrypto(journal);
    List<Entry> journalEntries = journalEntryManager.list(crypto, ctag, MAX_FETCH);
    return transform(journalEntries, e -> Pair.create(e, SyncEntry.fromJournalEntry(crypto, e)));
  }

  void pushEntries(Journal journal, List<Entry> entries, String ctag) throws HttpException {
    new JournalEntryManager(httpClient, httpUrl, journal.getUid()).create(entries, ctag);
  }

  public EteSyncClient setForeground() {
    foreground = true;
    return this;
  }
}
