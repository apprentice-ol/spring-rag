package com.nageoffer.ai.rag.storage.service;

import java.io.InputStream;

import com.nageoffer.ai.rag.storage.domian.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口。
 * <p>
 * 负责文件的上传、读取、删除等操作。
 * 底层存储由 {@link ObjectStorageClient} 实现，本接口负责 namespace 组装、
 * 类型探测与 DTO 装配。
 * </p>
 */
public interface FileStorageService {

    /** 上传文件（从 {@link MultipartFile}）。 */
    StoredFileDTO upload(String namespace, MultipartFile file);

    /** 上传文件（从 {@link InputStream}）。 */
    StoredFileDTO upload(String namespace, InputStream content, long size,
                         String originalFilename, String contentType);

    /** 上传文件（从 byte 数组）。 */
    StoredFileDTO upload(String namespace, byte[] content,
                         String originalFilename, String contentType);

    /** 可靠上传（SDK 重试机制）。 */
    StoredFileDTO reliableUpload(String namespace, InputStream content, long size,
                                  String originalFilename, String contentType);

    /** 上传公共资产（如图片等，人 asset bucket）。 */
    StoredFileDTO uploadAsset(byte[] content, String originalFilename, String contentType);

    /** 按 key 打开文件流。 */
    InputStream openStream(String key);

    /** 按 key 删除文件。 */
    void deleteByUrl(String key);

    /** 获取文件公开访问 URL（资产桶 assetBucket）。 */
    String getPublicUrl(String key);

    /** 获取文档桶公开访问 URL（kbBucket），用于源文件前端预览。 */
    default String getKbPublicUrl(String key) {
        return getPublicUrl(key);
    }

    /** 创建知识库空间（初始化桶目录）。 */
    void createKnowledgeSpace(String namespace);

    /** 删除知识库空间。 */
    void deleteKnowledgeSpace(String namespace);
}
