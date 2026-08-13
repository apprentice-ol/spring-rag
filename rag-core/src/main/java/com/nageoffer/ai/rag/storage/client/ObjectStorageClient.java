package com.nageoffer.ai.rag.storage.client;

import java.io.InputStream;

/**
 * 对象存储客户端接口。
 * <p>
 * 定义与对象存储（S3/OSS/MinIO）交互的原子操作。
 * 由各实现类按 {@code rag.storage.type} 切换。
 * </p>
 */
public interface ObjectStorageClient {

    /** 流式上传（通过预签名 URL，内存友好）。 */
    void streamPut(String bucket, String key, InputStream content, long size, String contentType);

    /** 可靠上传（SDK 内置重试）。 */
    void reliablePut(String bucket, String key, InputStream content, long size, String contentType);

    /** 获取对象流。 */
    InputStream getObject(String bucket, String key);

    /** 删除对象。 */
    void deleteObject(String bucket, String key);

    /** 按前缀删除对象。 */
    void deleteByPrefix(String bucket, String prefix);

    /** 判断对象是否存在。 */
    boolean objectExists(String bucket, String key);

    /** 判断桶是否存在。 */
    boolean bucketExists(String bucket);

    /** 创建桶。 */
    void createBucket(String bucket);

    /** 设置桶为公共读。 */
    void setBucketPublicRead(String bucket);

    /** 构建对象的公开访问 URL。 */
    String buildPublicUrl(String bucket, String key);
}
