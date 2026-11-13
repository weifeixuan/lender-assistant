package com.lender.assistant.common.web;

import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.model.dto.RoleBriefDTO;
import com.lender.assistant.account.service.AuthService;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.common.constant.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局认证拦截器契约：令牌缺失/格式非法/无效一律 401；已登录但缺权限点 403。
 * 这里直接驱动 preHandle，路径规则与请求方法由 MockHttpServletRequest 提供。
 */
class AuthInterceptorTest {

    private static final String TOKEN = "valid-token";

    private final AuthService authService = mock(AuthService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(authService);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Object handler = new Object();

    @Test
    @DisplayName("缺少 Authorization 头：401 登录已失效")
    void missingHeaderIsUnauthorized() {
        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(new MockHttpServletRequest("GET", "/api/conversations"),
                        response, handler));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode());
        assertEquals("登录已失效，请重新登录", exception.getMessage());
        verify(authService, never()).authenticate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("非 Bearer 前缀：401")
    void nonBearerPrefixIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Basic abc");

        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode());
    }

    @Test
    @DisplayName("合法令牌：写入账户标识与令牌摘要")
    void validTokenWritesAttributes() throws Exception {
        when(authService.authenticate(TOKEN)).thenReturn(account(List.of(
                PermissionCode.KNOWLEDGE_CHAT_USE.code())));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, handler), "有权限应放行");

        assertEquals("user-1", request.getAttribute(AuthConstants.USER_ID_ATTRIBUTE),
                "业务层按同名属性取账户标识");
        assertEquals("token-hash", request.getAttribute(AuthConstants.TOKEN_HASH_ATTRIBUTE),
                "登出按当前令牌摘要定位会话");
    }

    @Test
    @DisplayName("缺少所需权限点：403 无访问权限")
    void missingPermissionIsForbidden() {
        when(authService.authenticate(TOKEN)).thenReturn(account(List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN);

        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler));

        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode());
        assertEquals("无访问权限", exception.getMessage());
    }

    @Test
    @DisplayName("文档维护接口需要维护权限，查看接口只需查看权限")
    void documentPermissionsSplitByMethod() throws Exception {
        when(authService.authenticate(TOKEN)).thenReturn(account(List.of(
                PermissionCode.KNOWLEDGE_DOCUMENTS_VIEW.code())));

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/admin/documents");
        read.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN);
        assertTrue(interceptor.preHandle(read, response, handler), "查看权限应能读列表");

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/api/admin/documents");
        write.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN);
        assertThrows(BizException.class, () -> interceptor.preHandle(write, response, handler),
                "只有查看权限不能上传");
    }

    @Test
    @DisplayName("无页面入口的接口只要登录即可")
    void loginOnlyEndpointsPassWithAnyToken() throws Exception {
        when(authService.authenticate(TOKEN)).thenReturn(account(List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/extract/run");
        request.addHeader(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, handler), "未挂权限点的接口只需登录");
    }

    private static AuthenticatedAccountDTO account(List<String> permissions) {
        return new AuthenticatedAccountDTO("user-1", "admin", "管理员", "token-hash",
                List.of(new RoleBriefDTO("r1", "系统管理员")), permissions);
    }
}
