package io.github.jasperzxy.javamanus.controller;

import lombok.Data;

/**
 * 聊天请求 DTO
 */
@Data
public class ChatRequest {

    /** 用户输入的 prompt */
    private String prompt;
}
