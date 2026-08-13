package com.nageoffer.ai.rag.console.controller;

import com.nageoffer.ai.rag.console.domain.ConsoleOverview;
import com.nageoffer.ai.rag.console.service.ConsoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台总览接口。 */
@RestController
@RequestMapping("/console")
@RequiredArgsConstructor
public class ConsoleController {

    private final ConsoleService consoleService;

    /** 系统状态 + 业务统计 + 检索/入库配置只读视图 */
    @GetMapping("/overview")
    public ConsoleOverview overview() {
        return consoleService.overview();
    }
}
