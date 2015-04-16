package org.geowebcache.s3;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.io.ByteArrayResource;
import org.geowebcache.io.Resource;
import org.geowebcache.storage.BlobStore;
import org.geowebcache.storage.BlobStoreListener;
import org.geowebcache.storage.BlobStoreListenerList;
import org.geowebcache.storage.StorageException;
import org.geowebcache.storage.TileObject;
import org.geowebcache.storage.TileRange;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class S3BlobStore implements BlobStore {

    private static Log log = LogFactory.getLog(S3BlobStore.class);

    private final BlobStoreListenerList listeners = new BlobStoreListenerList();

    private AmazonS3 conn;

    private TransferManager transfers;

    private final S3BlobStoreConfig config;

    private final TMSKeyBuilder keyBuilder;

    private String bucketName;

    public S3BlobStore(S3BlobStoreConfig config) {
        checkNotNull(config.getAwsAccessKey(), "Access key not provided");
        checkNotNull(config.getAwsSecretKey(), "Secret key not provided");

        this.config = config;
        this.bucketName = config.getBucket();
        String prefix = config.getPrefix() == null ? "" : config.getPrefix();
        this.keyBuilder = new TMSKeyBuilder(prefix);

        String accessKey = config.getAwsAccessKey();
        String secretKey = config.getAwsSecretKey();
        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setProtocol(config.isUseHTTPS() ? Protocol.HTTPS : Protocol.HTTP);
        if (config.getMaxConnections() > 0) {
            clientConfig.setMaxConnections(config.getMaxConnections());
        }
        clientConfig.setProxyDomain(config.getProxyDomain());
        clientConfig.setProxyWorkstation(config.getProxyWorkstation());
        clientConfig.setProxyHost(config.getProxyHost());
        clientConfig.setProxyPort(config.getProxyPort());
        clientConfig.setProxyUsername(config.getProxyUsername());
        clientConfig.setProxyPassword(config.getProxyPassword());

        log.debug("Initializing AWS S3 connection");
        this.conn = new AmazonS3Client(awsCredentials, clientConfig);

        try {
            log.debug("Checking access rights to bucket " + bucketName);
            AccessControlList bucketAcl = this.conn.getBucketAcl(bucketName);
            List<Grant> grants = bucketAcl.getGrantsAsList();
            log.debug("Bucket " + bucketName + " permissions: " + grants);
        } catch (AmazonServiceException se) {
            throw new RuntimeException("Server error listing buckets: " + se.getMessage(), se);
        } catch (AmazonClientException ce) {
            throw new IllegalArgumentException("Unable to connect to AWS S3", ce);
        }
        int nThreads = clientConfig.getMaxConnections();
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(
                "AWS S3 blobstore - %d").build();
        ExecutorService threadPool = Executors.newFixedThreadPool(nThreads, threadFactory);
        boolean shutdownThreadPools = true;
        this.transfers = new TransferManager(conn, threadPool, shutdownThreadPools);
    }

    @Override
    public void destroy() {
        if (conn != null) {
            // shuts down TransferManager AND AmazonS3Client
            transfers.shutdownNow();
            transfers = null;
            conn = null;
        }
    }

    @Override
    public void addListener(BlobStoreListener listener) {
        listeners.addListener(listener);
    }

    @Override
    public boolean removeListener(BlobStoreListener listener) {
        return listeners.removeListener(listener);
    }

    @Override
    public void put(TileObject obj) throws StorageException {
        final Resource blob = obj.getBlob();
        checkNotNull(blob);
        checkNotNull(obj.getBlobFormat());

        final String key = keyBuilder.forTile(obj);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(blob.getSize());
        objectMetadata.setContentType(obj.getBlobFormat());

        // don't bother for the extra call if there are no listeners
        final boolean existed;
        ObjectMetadata oldObj;
        if (listeners.isEmpty()) {
            existed = false;
            oldObj = null;
        } else {
            try {
                oldObj = conn.getObjectMetadata(bucketName, key);
            } catch (AmazonS3Exception e) {
                if (404 != e.getStatusCode()) {// 404 == not found
                    throw new StorageException("Error checking existence of " + key + ": "
                            + e.getMessage(), e);
                }
                oldObj = null;
            }
            existed = oldObj != null;
        }

        final ByteArrayInputStream input = toByteArray(blob);
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, input,
                objectMetadata);

        log.trace(log.isTraceEnabled() ? ("Storing " + key) : "");
        try {
            conn.putObject(putObjectRequest);
        } catch (RuntimeException e) {
            throw new StorageException("Error storing " + key, e);
        }

        /*
         * This is important because listeners may be tracking tile existence
         */
        if (!listeners.isEmpty()) {
            if (existed) {
                long oldSize = oldObj.getContentLength();
                listeners.sendTileUpdated(obj, oldSize);
            } else {
                listeners.sendTileStored(obj);
            }
        }
    }

    private ByteArrayInputStream toByteArray(final Resource blob) throws StorageException {
        final byte[] bytes;
        if (blob instanceof ByteArrayResource) {
            bytes = ((ByteArrayResource) blob).getContents();
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) blob.getSize());
            WritableByteChannel channel = Channels.newChannel(out);
            try {
                blob.transferTo(channel);
            } catch (IOException e) {
                throw new StorageException("Error copying blob contents", e);
            }
            bytes = out.toByteArray();
        }
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        return input;
    }

    @Override
    public boolean get(TileObject obj) throws StorageException {
        final String key = keyBuilder.forTile(obj);
        final S3Object object = getObject(key);
        if (object == null) {
            return false;
        }
        try (S3ObjectInputStream in = object.getObjectContent()) {
            byte[] bytes = ByteStreams.toByteArray(in);
            obj.setBlobSize(bytes.length);
            obj.setBlob(new ByteArrayResource(bytes));
            obj.setCreated(object.getObjectMetadata().getLastModified().getTime());
        } catch (IOException e) {
            throw new StorageException("Error getting " + key, e);
        }
        return true;
    }

    @Override
    public boolean delete(TileRange obj) throws StorageException {

        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean delete(String layerName) throws StorageException {
        String layerPrefix = keyBuilder.forLayer(layerName);
        final long tileCount = deleteTiles(layerPrefix);
        final String metadataKey = keyBuilder.layerMetadata(layerName);
        boolean layerExisted;
        if (tileCount == 0) {
            S3Object layerMetadata = getObject(metadataKey);
            layerExisted = layerMetadata != null;
        } else {
            layerExisted = true;
        }
        if (layerExisted) {
            conn.deleteObject(bucketName, metadataKey);
            listeners.sendLayerDeleted(layerName);
        }

        return layerExisted;
    }

    @Override
    public boolean deleteByGridsetId(String layerName, String gridSetId) throws StorageException {
        final String gridsetPrefix = keyBuilder.forGridset(layerName, gridSetId);
        final long tileCount = deleteTiles(gridsetPrefix);
        if (tileCount > 0) {
            listeners.sendGridSubsetDeleted(layerName, gridSetId);
        }

        return tileCount > 0;
    }

    @Nullable
    private ObjectMetadata getObjectMetadata(String key) throws StorageException {
        try {
            return conn.getObjectMetadata(bucketName, key);
        } catch (AmazonS3Exception e) {
            if (404 != e.getStatusCode()) {// 404 == not found
                throw new StorageException("Error checking existence of " + key + ": "
                        + e.getMessage(), e);
            }
        }
        return null;
    }

    @Override
    public boolean delete(TileObject obj) throws StorageException {
        final String key = keyBuilder.forTile(obj);

        // don't bother for the extra call if there are no listeners
        ObjectMetadata oldObj = getObjectMetadata(key);

        if (oldObj == null) {
            return false;
        }

        conn.deleteObject(bucketName, key);
        obj.setBlobSize((int) oldObj.getContentLength());
        listeners.sendTileDeleted(obj);
        return true;
    }

    @Override
    public boolean rename(String oldLayerName, String newLayerName) throws StorageException {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void clear() throws StorageException {
        throw new UnsupportedOperationException("clear() should not be called");
    }

    @Nullable
    @Override
    public String getLayerMetadata(String layerName, String key) {
        Properties properties = getLayerMetadata(layerName);
        String value = properties.getProperty(key);
        return value;
    }

    @Override
    public void putLayerMetadata(String layerName, String key, String value) {
        Properties properties = getLayerMetadata(layerName);
        properties.setProperty(key, value);
        String resourceKey = keyBuilder.layerMetadata(layerName);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            properties.store(out, "");
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }

        byte[] bytes = out.toByteArray();
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(bytes.length);
        objectMetadata.setContentType("text/plain");

        InputStream in = new ByteArrayInputStream(bytes);
        PutObjectRequest putReq = new PutObjectRequest(bucketName, resourceKey, in, objectMetadata);
        conn.putObject(putReq);
    }

    private Properties getLayerMetadata(String layerName) {
        Properties properties = new Properties();
        byte[] bytes;
        try {
            bytes = getBytes(keyBuilder.layerMetadata(layerName));
        } catch (StorageException e) {
            throw Throwables.propagate(e);
        }
        if (bytes != null) {
            try {
                properties.load(new InputStreamReader(new ByteArrayInputStream(bytes),
                        Charsets.UTF_8));
            } catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }
        return properties;
    }

    @Nullable
    private S3Object getObject(String key) throws StorageException {
        final S3Object object;
        try {
            object = conn.getObject(bucketName, key);
        } catch (AmazonS3Exception e) {
            if (404 == e.getStatusCode()) {// 404 == not found
                return null;
            }
            throw new StorageException("Error fetching " + key + ": " + e.getMessage(), e);
        }
        return object;
    }

    @Nullable
    private byte[] getBytes(String key) throws StorageException {
        S3Object object = getObject(key);
        if (object == null) {
            return null;
        }
        try (S3ObjectInputStream in = object.getObjectContent()) {
            byte[] bytes = ByteStreams.toByteArray(in);
            return bytes;
        } catch (IOException e) {
            throw new StorageException("Error getting " + key, e);
        }

    }

    /**
     * Deletes all tiles under the given prefix and returns the number of tiles deleted
     */
    private long deleteTiles(String keyPrefix) {
        Iterable<S3ObjectSummary> objects = S3Objects.withPrefix(conn, bucketName, keyPrefix);
        Iterable<List<S3ObjectSummary>> partitions = Iterables.partition(objects, 1000);

        long count = 0;
        for (List<S3ObjectSummary> partition : partitions) {
            List<KeyVersion> keys = new ArrayList<>(partition.size());
            for (S3ObjectSummary so : partition) {
                String key = so.getKey();
                if (!key.endsWith(TMSKeyBuilder.LAYER_METADATA_OBJECT_NAME)) {
                    keys.add(new KeyVersion(key));
                }
            }
            if (!keys.isEmpty()) {
                DeleteObjectsRequest deleteReq = new DeleteObjectsRequest(bucketName);
                deleteReq.setQuiet(true);
                deleteReq.setKeys(keys);
                conn.deleteObjects(deleteReq);
                count += keys.size();
            }
        }
        return count;
    }
}
