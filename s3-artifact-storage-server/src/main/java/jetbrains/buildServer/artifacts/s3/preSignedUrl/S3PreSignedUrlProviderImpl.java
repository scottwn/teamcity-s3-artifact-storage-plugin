package jetbrains.buildServer.artifacts.s3.preSignedUrl;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.amazon.AWSCommonParams;
import jetbrains.buildServer.util.amazon.AWSException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Created by Evgeniy Koshkin (evgeniy.koshkin@jetbrains.com) on 19.07.17.
 */
public class S3PreSignedUrlProviderImpl implements S3PreSignedUrlProvider {
  private static final Logger LOG = Logger.getInstance(S3PreSignedUrlProviderImpl.class.getName());

  private static final String TEAMCITY_S3_PRESIGNURL_GET_CACHE_ENABLED = "teamcity.s3.presignurl.get.cache.enabled";

  private final Cache<String, String> myGetLinksCache = CacheBuilder.newBuilder()
    .expireAfterWrite(getUrlLifetimeSec(), TimeUnit.SECONDS)
    .maximumSize(200)
    .build();

  @Override
  public int getUrlLifetimeSec() {
    return TeamCityProperties.getInteger(S3Constants.S3_URL_LIFETIME_SEC, S3Constants.DEFAULT_S3_URL_LIFETIME_SEC);
  }

  @NotNull
  @Override
  public String getPreSignedUrl(@NotNull HttpMethod httpMethod, @NotNull String bucketName, @NotNull String objectKey, @NotNull Map<String, String> params) throws IOException {
    try {
      final Callable<String> resolver = () -> S3Util.withS3Client(params, client -> {
        final GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectKey, httpMethod)
          .withExpiration(new Date(System.currentTimeMillis() + getUrlLifetimeSec() * 1000));
        return client.generatePresignedUrl(request).toString();
      });
      if (httpMethod == HttpMethod.GET) {
        return TeamCityProperties.getBoolean(TEAMCITY_S3_PRESIGNURL_GET_CACHE_ENABLED)
          ? myGetLinksCache.get(getCacheIdentity(params, objectKey, bucketName), resolver)
          : resolver.call();
      } else {
        return resolver.call();
      }
    } catch (Exception e) {
      final AWSException awsException = new AWSException(e.getCause());
      if (StringUtil.isNotEmpty(awsException.getDetails())) {
        LOG.warn(awsException.getDetails());
      }
      final String err = "Failed to create " + httpMethod.name() + " pre-signed URL for [" + objectKey + "] in bucket [" + bucketName + "]";
      LOG.infoAndDebugDetails(err, awsException);
      throw new IOException(err + ": " + awsException.getMessage(), awsException);
    }
  }

  @NotNull
  private String getCacheIdentity(@NotNull Map<String, String> params, @NotNull String key, @NotNull String bucket) {
    return String.valueOf(AWSCommonParams.calculateIdentity("", params, bucket, key));
  }
}
