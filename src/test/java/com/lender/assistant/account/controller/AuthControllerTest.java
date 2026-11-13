package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.response.AuthAccountResponse;
import com.lender.assistant.account.model.response.LoginResponse;
import com.lender.assistant.account.model.response.RoleBriefResponse;
import com.lender.assistant.account.service.AuthService;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录/登出/当前账户的 HTTP 契约。登录接口本身不做令牌校验（由 WebMvcConfig 排除），
 * 因此这里不注册认证拦截器；me / logout 所需的请求属性由测试直接注入。
 */
class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("正确凭据登录：返回令牌、到期时间与账户信息")
    void loginReturnsTokenAndAccount() throws Exception {
        when(authService.login("admin", "password123")).thenReturn(new LoginResponse(
                "tok", "2026-11-20 10:00:00", new AuthAccountResponse(
                "user-1", "admin", "管理员",
                List.of(new RoleBriefResponse("r1", "系统管理员")),
                List.of("knowledge.chat.use"))));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("tok"))
                .andExpect(jsonPath("$.data.expireTime").value("2026-11-20 10:00:00"))
                .andExpect(jsonPath("$.data.user.userId").value("user-1"))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                .andExpect(jsonPath("$.data.user.roles[0].name").value("系统管理员"))
                .andExpect(jsonPath("$.data.user.permissions[0]").value("knowledge.chat.use"));
    }

    @Test
    @DisplayName("用户名空白：400 PARAM_INVALID，不进入凭据校验")
    void blankUsernameIsParameterError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"  \",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("凭据错误：401 且文案为统一提示")
    void badCredentialsReturn401() throws Exception {
        when(authService.login("admin", "wrong"))
                .thenThrow(new BizException(ErrorCode.UNAUTHORIZED, AuthConstants.LOGIN_FAILED_MESSAGE));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("登出：按当前令牌摘要删除会话")
    void logoutDeletesCurrentToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .requestAttr(AuthConstants.TOKEN_HASH_ATTRIBUTE, "current-hash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authService).logout("current-hash");
    }

    @Test
    @DisplayName("当前账户：返回角色与权限点")
    void meReturnsAccount() throws Exception {
        when(authService.currentAccount("user-1")).thenReturn(new AuthAccountResponse(
                "user-1", "admin", "管理员", List.of(),
                List.of("knowledge.chat.use")));

        mockMvc.perform(get("/api/auth/me")
                        .requestAttr(AuthConstants.USER_ID_ATTRIBUTE, "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.permissions[0]").value("knowledge.chat.use"));
    }
}
