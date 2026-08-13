package com.nageoffer.ai.rag.diagnose.controller;

import com.nageoffer.ai.rag.diagnose.dto.DiagnoseResponse;
import com.nageoffer.ai.rag.diagnose.service.LogDiagnoseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志诊断入口：输入 traceId，返回该链路的日志、报错、相关文档与修复建议。
 *
 * <p>Knife4j 自动生成文档（/api/rag/doc.html）。
 */
@Slf4j
@RestController
@RequestMapping("/diagnose")
@RequiredArgsConstructor
public class DiagnoseController {

    private final LogDiagnoseService logDiagnoseService;

    /**
     * 按 traceId 诊断。
     *
     * @param traceId 链路 ID（从日志的 [traceId,spanId] 取）
     */
    @PostMapping("/trace")
    public DiagnoseResponse diagnose(@RequestParam String traceId) {
        return logDiagnoseService.diagnose(traceId);
    }
}
