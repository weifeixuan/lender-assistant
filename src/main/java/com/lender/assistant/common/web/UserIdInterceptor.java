package com.lender.assistant.common.web;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

/**
 * 用户标识解析：把 X-User-Id 校验后写入 request attribute，供 controller 以
 * {@code @RequestAttribute} 取用。
 *
 * 不用 ThreadLocal：异步流式任务会跨线程，ThreadLocal 在请求线程写入后无法可靠传给工作线程；
 * request attribute 只服务于同步请求链路，流式任务所需的 userId 由 service 显式传入。
 */
@Component
public class UserIdInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";
    public static final String HEADER = "X-User-Id";

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String userId = request.getHeader(HEADER);
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "X-User-Id 缺失或格式非法（允许 1-64 位字母、数字、下划线、连字符）");
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
