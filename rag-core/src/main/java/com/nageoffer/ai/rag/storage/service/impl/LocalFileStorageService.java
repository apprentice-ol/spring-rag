package com.nageoffer.ai.rag.storage.service.impl;

import com.nageoffer.ai.rag.storage.service.FileStorageService;
import com.nageoffer.ai.rag.storage.domian.dto.StoredFileDTO;
import com.nageoffer.ai.rag.common.util.FileTypeDetector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件存储实现。
 * <p>
 * 文件存储到本地磁盘，通过 {@code rag.storage.type=local} 启用。
 * 目录：${java.io.tmpdir}/springai-rag-storage
 * </p>
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path storageDir;

    public LocalFileStorageService() throws IOException {
        this.storageDir = Path.of(System.getProperty("java.io.tmpdir"), "springai-rag-storage");
        Files.createDirectories(this.storageDir);
    }

    @Override
    public StoredFileDTO upload(String namespace, MultipartFile file) {
        String filename = file.getOriginalFilename();
        Path dest = resolvePath(namespace, filename);
        try {
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("本地文件上传失败", e);
        }
        long size = file.getSize();
        String contentType = file.getContentType();
        return new StoredFileDTO(dest.toString(), FileTypeDetector.detectType(filename, contentType),
                contentType, size, filename);
    }

    @Override
    public StoredFileDTO upload(String namespace, InputStream content, long size,
                                String originalFilename, String contentType) {
        Path dest = resolvePath(namespace, originalFilename);
        try {
            Files.copy(content, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("本地文件上传失败", e);
        }
        return new StoredFileDTO(dest.toString(), FileTypeDetector.detectType(originalFilename, contentType),
                contentType, size, originalFilename);
    }

    @Override
    public StoredFileDTO upload(String namespace, byte[] content,
                                String originalFilename, String contentType) {
        return upload(namespace, new ByteArrayInputStream(content), content.length,
                originalFilename, contentType);
    }

    @Override
    public StoredFileDTO reliableUpload(String namespace, InputStream content, long size,
                                         String originalFilename, String contentType) {
        // 本地文件系统无需重试，直接委托 upload
        return upload(namespace, content, size, originalFilename, contentType);
    }

    @Override
    public StoredFileDTO uploadAsset(byte[] content, String originalFilename, String contentType) {
        return upload("_assets", new ByteArrayInputStream(content), content.length,
                originalFilename, contentType);
    }

    /**
     * 保存上传文件到本地临时目录（MQ 异步走本地临时路径用，非标准接口方法）。
     *
     * @param file  上传文件
     * @param docId 文档 ID
     * @return 物理文件路径
     */
    public Path save(MultipartFile file, String docId) {
        String original = (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
                ? "file" : file.getOriginalFilename();
        Path dest = storageDir.resolve(docId + "-" + original);
        try {
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("本地临时文件保存失败", e);
        }
        return dest;
    }

    @Override
    public InputStream openStream(String key) {
        try {
            return Files.newInputStream(Path.of(key));
        } catch (IOException e) {
            throw new RuntimeException("本地文件读取失败: " + key, e);
        }
    }

    @Override
    public void deleteByUrl(String key) {
        try {
            Files.deleteIfExists(Path.of(key));
        } catch (IOException e) {
            throw new RuntimeException("本地文件删除失败: " + key, e);
        }
    }

    @Override
    public String getPublicUrl(String key) {
        return Path.of(key).toUri().toString();
    }

    @Override
    public void createKnowledgeSpace(String namespace) {
        // 本地模式知识库空间 = 目录，已由 resolvePath 自动创建
    }

    @Override
    public void deleteKnowledgeSpace(String namespace) {
        // 本地模式无需额外处理
    }

    private Path resolvePath(String namespace, String filename) {
        String safeName = (filename == null || filename.isBlank()) ? "file" : filename;
        String uniqueName = UUID.randomUUID().toString().replace("-", "") + "-" + safeName;
        Path dir = storageDir.resolve(namespace);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + dir, e);
        }
        return dir.resolve(uniqueName);
    }
}
