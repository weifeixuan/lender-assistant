package com.lender.assistant.common.web;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户标识拦截器的契约：合法头写入 request attribute；缺失/非法一律 400，
 * 把"没有用户身份"挡在业务层之前（§5.3 / ADR-0009）。
 */
class UserIdInterceptorTest {

    private final UserIdInterceptor interceptor = new UserIdInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Object handler = new Object();

    @Test
    @DisplayName("合法 X-User-Id 写入 request attribute")
    void validHeaderSetsAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "dev-user_001");

        interceptor.preHandle(request, response, handler);

        assertEquals("dev-user_001", request.getAttribute(UserIdInterceptor.USER_ID_ATTRIBUTE));
    }

    @Test
    @DisplayName("缺失 X-User-Id 返回 PARAM_INVALID")
    void missingHeaderIsRejected() {
        assertParamInvalid(new MockHttpServletRequest());
    }

    @Test
    @DisplayName("空白 X-User-Id 返回 PARAM_INVALID")
    void blankHeaderIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "   ");
        assertParamInvalid(request);
    }

    @Test
    @DisplayName("含非法字符的 X-User-Id 返回 PARAM_INVALID")
    void illegalCharactersAreRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "dev user!");
        assertParamInvalid(request);
    }

    @Test
    @DisplayName("超过 64 字符的 X-User-Id 返回 PARAM_INVALID")
    void tooLongHeaderIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "a".repeat(65));
        assertParamInvalid(request);
    }

    private void assertParamInvalid(MockHttpServletRequest request) {
        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler));
        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }
}
