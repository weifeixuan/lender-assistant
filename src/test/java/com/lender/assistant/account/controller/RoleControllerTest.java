package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.response.PermissionResponse;
import com.lender.assistant.account.model.response.RoleResponse;
import com.lender.assistant.account.service.RoleService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理接口 HTTP 契约：列表形状、权限点目录、增改删与错误包装。
 */
class RoleControllerTest {

    private RoleService roleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RoleController(roleService))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("角色列表：返回内置标记与引用账户数")
    void listReturnsRoles() throws Exception {
        when(roleService.listRoles()).thenReturn(List.of(new RoleResponse(
                "r1", "系统管理员", "全部权限", List.of(), 1, true)));

        mockMvc.perform(get("/api/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("系统管理员"))
                .andExpect(jsonPath("$.data[0].accountCount").value(1))
                .andExpect(jsonPath("$.data[0].builtin").value(true));
    }

    @Test
    @DisplayName("权限点目录：返回 5 个固定权限点")
    void listPermissions() throws Exception {
        when(roleService.listPermissions()).thenReturn(List.of(
                new PermissionResponse("knowledge.documents.view", "查看文档")));

        mockMvc.perform(get("/api/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("knowledge.documents.view"))
                .andExpect(jsonPath("$.data[0].name").value("查看文档"));
    }

    @Test
    @DisplayName("新建角色：报文映射到服务")
    void createRole() throws Exception {
        when(roleService.createRole(any())).thenReturn(new RoleResponse(
                "r1", "文档只读", "只读", List.of("knowledge.documents.view"), 0, false));

        mockMvc.perform(post("/api/roles")
                        .contentType("application/json")
                        .content("{\"name\":\"文档只读\",\"description\":\"只读\","
                                + "\"permissionCodes\":[\"knowledge.documents.view\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("文档只读"));
    }

    @Test
    @DisplayName("编辑内置角色：400 + ROLE_PROTECTED")
    void updateBuiltinReturns400() throws Exception {
        when(roleService.updateRole(eq("r1"), any()))
                .thenThrow(new BizException(ErrorCode.ROLE_PROTECTED));

        mockMvc.perform(put("/api/roles/r1")
                        .contentType("application/json")
                        .content("{\"name\":\"改名\",\"description\":null,\"permissionCodes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("内置角色不允许修改"));
    }

    @Test
    @DisplayName("删除仍被引用的角色：409 + 引用数量文案")
    void deleteReferencedReturns409() throws Exception {
        doThrow(new BizException(ErrorCode.ROLE_IN_USE, "角色仍被 3 个账户使用"))
                .when(roleService).deleteRole("r1");

        mockMvc.perform(delete("/api/roles/r1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("角色仍被 3 个账户使用"));
    }

    @Test
    @DisplayName("删除自定义角色：成功")
    void deleteRole() throws Exception {
        mockMvc.perform(delete("/api/roles/r1"))
                .andExpect(status().isOk());

        verify(roleService).deleteRole("r1");
    }
}
