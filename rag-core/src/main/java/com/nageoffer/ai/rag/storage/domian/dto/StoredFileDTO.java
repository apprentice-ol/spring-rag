package com.nageoffer.ai.rag.storage.domian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文件存储结果 DTO。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StoredFileDTO {

    /** 对象 key（或 URL） */
    private String url;

    /** 检测的文件类型（如 pdf、markdown） */
    private String detectedType;

    /** MIME 类型 */
    private String mimeType;

    /** 文件大小（字节） */
    private Long size;

    /** 原始文件名 */
    private String originalFilename;
}
