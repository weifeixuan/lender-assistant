package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.response.AccountResponse;
import com.lender.assistant.account.model.response.RoleBriefResponse;
import com.lender.assistant.account.service.AccountService;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.web.ApiResponseBodyAdvice;
import com.lender.assistant.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户管理接口 HTTP 契约：路径、入出参形状与错误包装。
 * 五条硬约束的业务判定在服务层测试覆盖，这里只验证路由与报文映射。
 */
class AccountControllerTest {

    private AccountService accountService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("列表：返回账户条目数组")
    void listReturnsAccounts() throws Exception {
        when(accountService.listAccounts()).thenReturn(List.of(new AccountResponse(
                "u1", "alice", "爱丽丝",
                List.of(new RoleBriefResponse("r1", "文档只读")), "ENABLED", "2026-11-13 10:00:00")));

        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].roles[0].name").value("文档只读"))
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].createTime").value("2026-11-13 10:00:00"));
    }

    @Test
    @DisplayName("新建：报文映射到服务并返回账户条目")
    void createReturnsAccount() throws Exception {
        when(accountService.createAccount(any())).thenReturn(new AccountResponse(
                "u1", "alice", "爱丽丝", List.of(), "ENABLED", "2026-11-13 10:00:00"));

        mockMvc.perform(post("/api/accounts")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"displayName\":\"爱丽丝\","
                                + "\"password\":\"password123\",\"roleIds\":[\"r1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("u1"));
    }

    @Test
    @DisplayName("新建：用户名重复返回 409 与明确文案")
    void duplicateUsernameReturns409() throws Exception {
        when(accountService.createAccount(any()))
                .thenThrow(new BizException(ErrorCode.USERNAME_EXISTS));

        mockMvc.perform(post("/api/accounts")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"displayName\":\"爱丽丝\","
                                + "\"password\":\"password123\",\"roleIds\":[\"r1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }

    @Test
    @DisplayName("改自己的口令：固定路径优先于按账户标识的路径")
    void changeOwnPasswordMatchesFixedPathFirst() throws Exception {
        mockMvc.perform(put("/api/accounts/me/password")
                        .requestAttr(AuthConstants.USER_ID_ATTRIBUTE, "u1")
                        .requestAttr(AuthConstants.TOKEN_HASH_ATTRIBUTE, "hash")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"password123\",\"newPassword\":\"new-password-1\"}"))
                .andExpect(status().isOk());

        verify(accountService).changeOwnPassword(eq("u1"), eq("hash"), any());
        verify(accountService, never()).resetPassword(any(), any());
    }

    @Test
    @DisplayName("重置他人口令：按账户标识路由")
    void resetPasswordRoutesByUserId() throws Exception {
        mockMvc.perform(put("/api/accounts/u2/password")
                        .contentType("application/json")
                        .content("{\"newPassword\":\"new-password-1\"}"))
                .andExpect(status().isOk());

        verify(accountService).resetPassword("u2", "new-password-1");
    }

    @Test
    @DisplayName("改资料：返回更新结果")
    void updateReturnsAccount() throws Exception {
        when(accountService.updateAccount(eq("u2"), any())).thenReturn(new AccountResponse(
                "u2", "alice", "爱丽丝二号", List.of(), "ENABLED", "2026-11-13 10:00:00"));

        mockMvc.perform(put("/api/accounts/u2")
                        .requestAttr(AuthConstants.USER_ID_ATTRIBUTE, "u1")
                        .contentType("application/json")
                        .content("{\"displayName\":\"爱丽丝二号\",\"roleIds\":[\"r1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("爱丽丝二号"));
    }

    @Test
    @DisplayName("改状态：返回更新后的账户条目")
    void updateStatusReturnsAccount() throws Exception {
        when(accountService.updateStatus(eq("u1"), eq("u2"), eq("DISABLED"))).thenReturn(
                new AccountResponse("u2", "alice", "爱丽丝", List.of(), "DISABLED", "2026-11-13 10:00:00"));

        mockMvc.perform(put("/api/accounts/u2/status")
                        .requestAttr(AuthConstants.USER_ID_ATTRIBUTE, "u1")
                        .contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }
}
