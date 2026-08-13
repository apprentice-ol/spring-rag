package com.nageoffer.ai.rag.storage.controller;

import com.nageoffer.ai.rag.storage.service.FileStorageService;
import com.nageoffer.ai.rag.storage.domian.dto.StoredFileDTO;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 文件存储 API（通过 FileStorageService 统一接口调用，底层由配置切换 S3/Local/...）。 */
@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
public class StorageController {

    private final FileStorageService fileStorageService;

    /** 上传文件到指定命名空间。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredFileDTO upload(@RequestParam String namespace,
                                @RequestParam("file") MultipartFile file) throws IOException {
        return fileStorageService.upload(namespace, file);
    }

    /** 上传公共资产（如图片），入 asset 桶。 */
    @PostMapping(value = "/upload/asset", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredFileDTO uploadAsset(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        return fileStorageService.uploadAsset(file.getBytes(), filename, file.getContentType());
    }

    /** 获取文件流。 */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String key) {
        InputStream stream = fileStorageService.openStream(key);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    /** 获取文件公开访问 URL。 */
    @GetMapping("/url")
    public String getUrl(@RequestParam String key) {
        return fileStorageService.getPublicUrl(key);
    }

    /** 删除文件。 */
    @DeleteMapping("/delete")
    public void delete(@RequestParam String key) {
        fileStorageService.deleteByUrl(key);
    }
}
