package edu.ouc.filter;

import edu.ouc.entity.User;
import edu.ouc.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.WebApplicationContext;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 设备身份解析回归测试（纯 Mockito，不启动 Spring、不连库）。
 *
 * 锁定的缺陷（2026-10-18 线上现象：加购成功但购物车列表为空）：
 *   购物车查询走白名单分支按 Cookie 解析身份，而加购/减菜/下单走 C 端分支直接信 Session。
 *   当 Cookie 指向的账号已被清理、而 Session 里还缓存着同一个旧 uid 时，
 *   旧实现会把这个"user 表里已不存在"的幽灵账号继续返回：
 *     - add 把购物车写进幽灵账号 → 返回成功；
 *     - list 换个身份或查不到 → 空列表 / 下单失败。
 *
 * 本测试保证：resolveDemoUserId 永远只返回"数据库中真实存在且启用"的账号。
 */
class LoginCheckFilterTest {

    /** 与 LoginCheckFilter 内部常量保持一致 */
    private static final String DEMO_SESSION_KEY = "__demo_user_id__";
    private static final String DEMO_COOKIE = "ouc_demo_uid";

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;
    @Mock
    private WebApplicationContext context;
    @Mock
    private Environment environment;
    @Mock
    private IUserService userService;

    private LoginCheckFilter filter;
    private AutoCloseable mocks;
    /** 用 Map 模拟真实 Session 的读写删语义（Mockito 默认每次 get 都返回同一桩值，无法体现 remove） */
    private final java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        filter = new LoginCheckFilter();
        // 绕过 WebApplicationContextUtils 静态查找：直接注入缓存上下文
        ReflectionTestUtils.setField(filter, "cachedContext", context);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("reggie.demo-user-id")).thenReturn(null);
        when(context.getBean(IUserService.class)).thenReturn(userService);
        when(request.getSession()).thenReturn(session);

        sessionAttrs.clear();
        when(session.getAttribute(anyString()))
                .thenAnswer(inv -> sessionAttrs.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            sessionAttrs.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return null;
        }).when(session).setAttribute(anyString(), any());
        doAnswer(inv -> {
            sessionAttrs.remove(inv.getArgument(0, String.class));
            return null;
        }).when(session).removeAttribute(anyString());
    }

    @AfterEach
    void tearDown() throws Exception {
        sessionAttrs.clear();
        mocks.close();
    }

    /** 预置 Session 中的属性 */
    private void givenSessionAttr(String key, Object value) {
        sessionAttrs.put(key, value);
    }

    // ------------------------------------------------------------------ 工具

    private static User user(Long id, int status) {
        User u = new User();
        u.setId(id);
        u.setStatus(status);
        return u;
    }

    /** 模拟浏览器携带的设备身份 Cookie */
    private void givenCookie(String value) {
        when(request.getCookies()).thenReturn(value == null
                ? null
                : new Cookie[]{new Cookie(DEMO_COOKIE, value)});
    }

    /** 让新建账号时 save() 回填主键（真实环境由雪花算法生成） */
    private void givenProvisionReturns(Long newId) {
        when(userService.save(any(User.class))).thenAnswer(inv -> {
            inv.getArgument(0, User.class).setId(newId);
            return true;
        });
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("Cookie 指向有效账号 → 直接沿用，不新建、不改 Cookie")
    void cookieValid_reusesAccount() {
        givenCookie("1001");
        when(userService.getById(1001L)).thenReturn(user(1001L, 1));

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(1001L, uid);
        verify(userService, never()).save(any(User.class));
        verify(response, never()).addHeader(eq("Set-Cookie"), any());
        verify(session).setAttribute(DEMO_SESSION_KEY, 1001L);
    }

    @Test
    @DisplayName("【回归】Cookie 账号已被删除 + Session 仍缓存旧 uid → 必须重建，绝不返回幽灵账号")
    void cookieAccountDeleted_neverReturnsGhostFromSession() {
        givenCookie("999");
        when(userService.getById(999L)).thenReturn(null);
        // 关键：Session 里还留着同一个旧 uid（旧实现会直接把它返回）
        givenSessionAttr(DEMO_SESSION_KEY, 999L);
        givenProvisionReturns(2002L);

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(2002L, uid, "必须返回新建的有效账号，而不是已删除的 999");
        assertNotEquals(999L, uid);
        // 旧身份必须被清掉，否则下一次请求又会读回幽灵账号
        verify(session).removeAttribute(DEMO_SESSION_KEY);
        verify(session).removeAttribute("user");
        // 新身份要下发到浏览器，保证后续请求一致
        verify(response).addHeader(eq("Set-Cookie"), contains(DEMO_COOKIE + "=2002"));
        verify(session).setAttribute(DEMO_SESSION_KEY, 2002L);
    }

    @Test
    @DisplayName("Cookie 账号被禁用（status≠1）→ 同样视为失效并重建")
    void cookieAccountDisabled_provisionsNew() {
        givenCookie("999");
        when(userService.getById(999L)).thenReturn(user(999L, 0));
        givenProvisionReturns(2003L);

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(2003L, uid);
        verify(userService).save(any(User.class));
    }

    @Test
    @DisplayName("无 Cookie 但 Session 缓存有效 → 复用缓存，不重复建号")
    void noCookie_sessionCachedValid_reuses() {
        givenCookie(null);
        givenSessionAttr(DEMO_SESSION_KEY, 1001L);
        when(userService.getById(1001L)).thenReturn(user(1001L, 1));

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(1001L, uid);
        verify(userService, never()).save(any(User.class));
    }

    @Test
    @DisplayName("无 Cookie 且 Session 缓存账号已失效 → 重建（不能沿用被清理的账号）")
    void noCookie_sessionCachedInvalid_provisionsNew() {
        givenCookie(null);
        givenSessionAttr(DEMO_SESSION_KEY, 999L);
        when(userService.getById(999L)).thenReturn(null);
        givenProvisionReturns(2004L);

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(2004L, uid);
        verify(userService).save(any(User.class));
    }

    @Test
    @DisplayName("首次访问（无 Cookie 无 Session）→ 新建设备专属账号并下发 Cookie")
    void firstVisit_provisionsAndSetsCookie() {
        givenCookie(null);
        givenProvisionReturns(3001L);

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(3001L, uid);
        verify(response).addHeader(eq("Set-Cookie"), contains("ouc_demo_uid=3001"));
        verify(session).setAttribute(DEMO_SESSION_KEY, 3001L);
    }

    @Test
    @DisplayName("Cookie 是非法值 → 不抛异常，按无身份处理并重建")
    void cookieNotNumeric_provisionsNew() {
        givenCookie("not-a-number");
        givenProvisionReturns(3002L);

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(3002L, uid);
        verify(userService).save(any(User.class));
    }

    @Test
    @DisplayName("数据库异常（无法判定账号有效性）→ 保守沿用旧身份，不误删不重建")
    void dbError_keepsExistingIdentity() {
        givenCookie("1001");
        when(userService.getById(1001L)).thenThrow(new RuntimeException("db down"));

        Long uid = filter.resolveDemoUserId(request, response, session);

        assertEquals(1001L, uid, "查询异常不等于账号不存在，应保守沿用");
        verify(userService, never()).save(any(User.class));
    }

    // ------------------------------------------------------------------ 真实登录保护

    @Test
    @DisplayName("真实登录会话（REAL_LOGIN_KEY）→ 不参与设备身份校正")
    void realLoginSession_notDemoContext() {
        givenSessionAttr(LoginCheckFilter.REAL_LOGIN_KEY, Boolean.TRUE);
        givenCookie("1001");

        assertFalse(filter.isDemoIdentityContext(request),
                "邮箱验证码登录的会话不能被演示设备账号顶掉");
    }

    @Test
    @DisplayName("演示会话（有设备 Cookie）→ 参与设备身份校正")
    void demoSession_isDemoContext() {
        givenCookie("1001");
        assertTrue(filter.isDemoIdentityContext(request));
    }

    @Test
    @DisplayName("全新会话（无 Cookie 无缓存）→ 不是演示身份上下文，交由首次建号流程处理")
    void freshSession_notDemoContext() {
        givenCookie(null);
        assertFalse(filter.isDemoIdentityContext(request));
    }
}
