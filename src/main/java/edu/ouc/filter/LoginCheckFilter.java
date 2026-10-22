package edu.ouc.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ouc.common.BaseContext;
import edu.ouc.common.R;
import edu.ouc.entity.User;
import edu.ouc.service.IUserService;
import edu.ouc.utils.TokenUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Author: Sihang Xie
 * Description: 用户登录检查过滤器
 * Date: 2022/9/30 9:47
 * Version: 0.0.1
 * Modified By:
 *
 * 2026-10-18 身份模型重构（设备级独立身份）：
 *   1. 每台设备（浏览器）首次访问时【新建一个专属 C 端账号】，身份写入 Cookie ouc_demo_uid。
 *      → 不再从固定账号池里轮流分配，彻底消除「两台手机拿到同一个账号 → 互相抢同一道菜」。
 *   2. 购物车作用域 = 账号 + 桌号：不同账号永不共享；同账号同桌号才共享（同桌拼单）。
 *   3. 桌台标识写入 BaseContext，供购物车/下单链路统一使用。
 *   4. 请求结束时清理 ThreadLocal，避免 Tomcat 线程复用导致身份/桌台串号。
 */
// 过滤器注解
// filterName：过滤器名称，可以随便起
// urlPatterns：想要拦截的URL地址，/*表示拦截所有请求
@WebFilter(filterName = "LoginCheckFilter", urlPatterns = "/*")
@Slf4j
public class LoginCheckFilter implements Filter {

    // Spring框架提供的路径匹配器，支持通配符
    public static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // Redis在线会话前缀
    private static final String ONLINE_EMPLOYEE_PREFIX = "online:employee:";
    private static final String ONLINE_USER_PREFIX = "online:user:";
    // 在线会话 Redis TTL（与 Session 超时一致，30分钟）
    private static final long ONLINE_TTL_MINUTES = 30;

    // 缓存 StringRedisTemplate，避免每次请求都从 Spring 容器获取
    private StringRedisTemplate stringRedisTemplate;
    private WebApplicationContext cachedContext;

    private StringRedisTemplate getRedisTemplate(HttpServletRequest request) {
        if (stringRedisTemplate == null) {
            WebApplicationContext context = WebApplicationContextUtils
                    .getWebApplicationContext(request.getServletContext());
            if (context != null) {
                stringRedisTemplate = context.getBean(StringRedisTemplate.class);
                cachedContext = context;
            }
        }
        return stringRedisTemplate;
    }

    private WebApplicationContext getSpringContext(HttpServletRequest request) {
        if (cachedContext == null) {
            cachedContext = WebApplicationContextUtils
                    .getWebApplicationContext(request.getServletContext());
        }
        return cachedContext;
    }

    // 验证当前 Session 是否仍为有效在线会话（多人同号登录互踢）
    // 2026-10-17 修复：互踢机制导致同一账号在手机+电脑、或多台手机同时打开时
    // 每次请求都被判"已被新登录踢下线" → 表现为页面反复跳登录、点击无反应、卡住。
    // 演示场景不需要互踢，直接放行（仅保留 TTL 续期逻辑）。
    private boolean validateOnlineSession(HttpServletRequest request, String type, Long userId) {
        StringRedisTemplate redis = getRedisTemplate(request);
        if (redis == null) {
            return true;
        }
        try {
            String key = ("employee".equals(type) ? ONLINE_EMPLOYEE_PREFIX : ONLINE_USER_PREFIX) + userId;
            // 尽力刷新 TTL；不再比对 SessionId，避免多端互踢
            redis.expire(key, ONLINE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Redis会话续期异常，忽略: {}", e.getMessage());
        }
        return true;
    }

    // 在Redis中注册在线会话（登录成功时调用）
    public static void trackOnlineSession(StringRedisTemplate redis, String sessionId, String type, Long userId) {
        if (redis == null) return;
        try {
            String key = ("employee".equals(type) ? ONLINE_EMPLOYEE_PREFIX : ONLINE_USER_PREFIX) + userId;
            redis.opsForValue().set(key, sessionId, ONLINE_TTL_MINUTES, TimeUnit.MINUTES);
            log.info("在线会话已注册: {}用户ID={}, sessionId={}", type, userId, sessionId);
        } catch (Exception e) {
            log.error("注册在线会话失败: {}", e.getMessage());
        }
    }

    // 从Redis中移除在线会话（退出登录时调用）
    public static void removeOnlineSession(StringRedisTemplate redis, String type, Long userId) {
        if (redis == null) return;
        try {
            String key = ("employee".equals(type) ? ONLINE_EMPLOYEE_PREFIX : ONLINE_USER_PREFIX) + userId;
            redis.delete(key);
            log.info("在线会话已移除: {}用户ID={}", type, userId);
        } catch (Exception e) {
            log.error("移除在线会话失败: {}", e.getMessage());
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        try {
            doFilterInternal((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse, filterChain);
        } finally {
            // Tomcat 线程复用：必须清理，否则上一个请求的用户/桌台会漏到下一个请求
            BaseContext.clear();
        }
    }

    private void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {

        // 1.获取本次请求的URL
        String requestURI = request.getRequestURI();    // /backend/index.html

        log.info("拦截到请求URL：{}", requestURI);

        // 1.1 解析本次请求的桌台标识（仅购物车/订单相关接口，避免在文件上传等请求上提前触发参数解析）
        if (requestURI.startsWith("/shoppingCart") || requestURI.startsWith("/order")) {
            BaseContext.setCurrentTableId(resolveTableId(request));
        }

        // 2.定义不需要拦截的URL地址数组
        String[] urls = new String[]{
                "/employee/login",  // 登录页面
                "/employee/logout", // 退出登录
                "/backend/**",      // 后台页面的页面的静态资源
                "/front/**",        // 移动端页面的静态资源
                "/user/login",      // 用户登录
                "/user/sendMsg",    // 发送登录验证码
                "/common/**",       // 文件上传下载（图片等静态资源，无需登录）
                "/kitchen/**",
			"/cashier/**",         // 收银台接口
			"/dinnerTable/**",     // 桌台管理接口
			"/announcement/active", // C端公告查询（无需登录）
            "/business/status", // C端营业状态查询（无需登录）
            // === C端首页只读菜单接口（必须免登录）===
            // 外卖类应用的首页（分类/菜品/套餐列表）允许游客浏览，登录只在下单时要求。
            // 否则未登录用户打开首页会立刻被前端拦截器跳转到登录页，
            // 表现为"首页一闪就跳到登录页"，无法查看菜单。
            "/category/list",   // C端菜品分类列表
            "/dish/list",       // C端菜品列表（按分类查询）
            "/setmeal/list",    // C端套餐列表（按分类查询）
            // C端套餐菜品明细：用于游客在首页点开套餐查看内含菜品（只读，无用户态数据）
            "/setmeal/dish/*",
            // C端购物车查询：首页初始渲染会调用，未登录时后端返回空列表（服务层已做 userId 空值保护，
            // 不会泄露其他用户数据）。加白名单避免首页游客被 NOTLOGIN 强制跳转到登录页。
            "/shoppingCart/list"
        };

        // 3.判断本次请求URL是否需要拦截
        Boolean check = check(urls, requestURI);

        // 4.如果check为true则不需要处理，直接放行
        if (check) {
            // 【重要】C 端只读接口（如 /shoppingCart/list）虽不强制登录，但在演示模式下
            // 仍要注入设备身份。否则 BaseContext 为空，用户自己的购物车永远是空列表，
            // 表现为"加了菜但购物车看不到"、"结算按钮一直是灰的"。
            // 注：只为 C 端接口注入，避免打开 /backend/index.html 等静态页时也去建账号。
            // 注：真实登录（邮箱+验证码）的会话不注入，否则会把真实账号顶成设备账号。
            if (AUTO_LOGIN_ENABLED && isClientApiPath(requestURI)
                    && !Boolean.TRUE.equals(request.getSession().getAttribute(REAL_LOGIN_KEY))) {
                try {
                    Long demoUserId = resolveDemoUserId(request, response, request.getSession());
                    if (demoUserId != null) {
                        request.getSession().setAttribute("user", demoUserId);
                        BaseContext.setCurrentUserId(demoUserId);
                    }
                } catch (Exception e) {
                    log.warn("C端只读接口注入设备身份失败(忽略): {}", e.getMessage());
                }
            }
            log.info("本次请求{}不需要处理", requestURI);
            filterChain.doFilter(request, response);
            return;
        }

        // 5.判断本次请求是否为C端业务接口（订单、购物车、C端用户）
        // C端接口优先使用user身份，避免与B端employee登录态冲突
        boolean isClientApi = requestURI.startsWith("/order/submit")
                || requestURI.startsWith("/order/userPage")
                || requestURI.startsWith("/order/requestRefund")
                || requestURI.startsWith("/order/refundStatus")
                || requestURI.startsWith("/order/again")
                || requestURI.startsWith("/order/addItems")
                || requestURI.startsWith("/order/pay")
                || requestURI.startsWith("/shoppingCart")
                // 注：/shoppingCart/list 与 /setmeal/dish/ 已在 urls 白名单中放行（游客可读）。
                // 这里保留分支仅作防御：若白名单规则调整，未登录访问仍按 user 会话校验而非降级为员工接口。
                || requestURI.startsWith("/setmeal/dish/")   // C端套餐菜品明细（点套餐弹详情用）
                || requestURI.startsWith("/user/loginout");

        // 6.判断是否为B端管理专用接口（只允许员工访问，不允许C端用户session降级或Cookie绕过）
        // 策略：所有 /order/* 中非 C端 API 的接口都是 B 端专用；/employee/* 全是 B 端；
        // /category、/dish、/setmeal 中除 /xxx/list 外的都是 B 端管理接口；
        // /business 中除 /business/status 外都是 B 端专用；/announcement 中除 /announcement/active 外都是 B 端专用
        boolean isEmployeeOnlyApi = requestURI.startsWith("/employee")
                || (requestURI.startsWith("/order") && !isClientApi)
                || (requestURI.startsWith("/category") && !requestURI.equals("/category/list"))
                || (requestURI.startsWith("/dish") && !requestURI.equals("/dish/list"))
                // 注意：/setmeal/list（C端套餐列表）与 /setmeal/dish/{id}（C端套餐菜品明细）
                // 都是 C 端首页所需接口，必须放行；否则点击套餐时返回 NOTLOGIN，
                // 前端无法弹出套餐详情，表现为"点套餐没反应"。
                || (requestURI.startsWith("/setmeal")
                    && !requestURI.equals("/setmeal/list")
                    && !requestURI.startsWith("/setmeal/dish/"))
                || (requestURI.startsWith("/business") && !requestURI.equals("/business/status"))
                || (requestURI.startsWith("/announcement") && !requestURI.equals("/announcement/active"));

        if (isClientApi) {
            // C端业务接口：优先检查user登录态（含Redis多端互踢校验）
            Object sessUserObj = request.getSession().getAttribute("user");
            Long userId = (sessUserObj instanceof Long) ? (Long) sessUserObj : null;

            // 【2026-10-18 修复·身份一致性】演示模式下设备 Cookie 是唯一可信来源。
            // 原实现里 /shoppingCart/list 走白名单分支（按 Cookie 解析身份），
            // 而 /shoppingCart/add 走这里（直接信 Session 里的 user），
            // 一旦两者指向不同账号（账号被清理重建、Session 残留旧账号），
            // 就会出现 add 写进 A 账号、list 读 B 账号 → "加购成功但购物车为空"。
            // 现在写接口也走同一套设备身份解析，并回写 Session，保证增删改查永远同一作用域。
            if (AUTO_LOGIN_ENABLED && isDemoIdentityContext(request)) {
                Long deviceUserId = resolveDemoUserId(request, response, request.getSession());
                if (deviceUserId != null) {
                    if (userId != null && !userId.equals(deviceUserId)) {
                        log.warn("C端身份校正：session user={} 与设备身份={} 不一致，以设备身份为准",
                                userId, deviceUserId);
                    }
                    userId = deviceUserId;
                    request.getSession().setAttribute("user", deviceUserId);
                }
            }

            if (userId != null) {
                if (!validateOnlineSession(request, "user", userId)) {
                    request.getSession().removeAttribute("user");
                    log.info("C端用户{}的会话已被新登录踢下线", userId);
                } else {
                    log.info("C端邮箱用户{}已登录", userId);
                    BaseContext.setCurrentUserId(userId);
                    filterChain.doFilter(request, response);
                    return;
                }
            }
        } else {
            // B端业务接口：优先检查employee登录态（含Redis多端互踢校验）
            if (request.getSession().getAttribute("employee") != null) {
                Long id = (Long) request.getSession().getAttribute("employee");
                if (!validateOnlineSession(request, "employee", id)) {
                    request.getSession().removeAttribute("employee");
                    log.info("B端员工{}的会话已被新登录踢下线", id);
                } else {
                    log.info("B端员工{}已登录", id);
                    BaseContext.setCurrentUserId(id);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // 非管理专用接口：允许C端用户session降级访问（如/category/list、/dish/list等C端也需要的接口）
            if (!isEmployeeOnlyApi && request.getSession().getAttribute("user") != null) {
                Long userId = (Long) request.getSession().getAttribute("user");
                if (!validateOnlineSession(request, "user", userId)) {
                    request.getSession().removeAttribute("user");
                    log.info("B端请求中C端用户{}的会话已被新登录踢下线", userId);
                } else {
                    log.info("B端请求使用C端用户{}身份", userId);
                    BaseContext.setCurrentUserId(userId);
                    filterChain.doFilter(request, response);
                    return;
                }
            }
        }

        log.info("B端员工和C端用户Session均未命中，尝试Cookie记住我自动登录");

        // 7.检查Cookie中的记住我Token，实现30天自动登录
        Cookie[] cookies = request.getCookies();
        String rememberToken = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("remember".equals(cookie.getName())) {
                    rememberToken = ***();
                    break;
                }
            }
        }
        if (rememberToken != null) {
            // 管理专用接口不允许Cookie记住我登陆（只允许员工登录）
            if (isEmployeeOnlyApi) {
                log.warn("拒绝Cookie记住我访问管理接口: requestURI={}", requestURI);
                response.getWriter().write(new ObjectMapper().writeValueAsString(R.error("NOTLOGIN")));
                return;
            }
            // 从Spring容器获取服务（使用缓存的上下文）
            WebApplicationContext context = getSpringContext(request);
            if (context != null) {
                // 读取配置中的签名密钥
                String rememberKey = context.getEnvironment()
                        .getProperty("reggie.remember-key");
                IUserService userService = context.getBean(IUserService.class);

                // 校验Token有效性
                Long userId = TokenUtils.validateRememberToken(rememberToken, rememberKey);
                if (userId != null) {
                    // 反查用户是否存在且未被禁用
                    User user = userService.getById(userId);
                    if (user != null && user.getStatus() == 1) {
                        // 自动登录成功，补设Session并注册Redis在线会话
                        request.getSession().setAttribute("user", userId);
                        // 注册在线会话（踢掉该用户在其他浏览器的旧会话）
                        StringRedisTemplate redis = getRedisTemplate(request);
                        trackOnlineSession(redis, request.getSession().getId(), "user", userId);
                        BaseContext.setCurrentUserId(userId);
                        log.info("Cookie记住我自动登录成功，用户ID：{}", userId);
                        filterChain.doFilter(request, response);
                        return;
                    }
                    log.info("Cookie记住我Token有效，但用户不存在或已被禁用，用户ID：{}", userId);
                }
            }
        }

        log.info("用户未登录");

        // 8.【演示模式】跳过登录校验：自动以设备专属账号身份放行。
        // 说明：本项目当前用于演示/答辩，C 端不再要求输入邮箱验证码。
        // 关键设计：每台设备分配一个【独立的】C 端账号（首次访问自动创建），
        // 而不是所有人都固定成同一个账号——否则不同手机/浏览器会被视为同一用户，
        // 购物车互相串菜（即用户反馈的"不同用户点击菜品出现共享"）。
        if (AUTO_LOGIN_ENABLED && !isEmployeeOnlyApi) {
            Long demoUserId = resolveDemoUserId(request, response, request.getSession());
            if (demoUserId != null) {
                request.getSession().setAttribute("user", demoUserId);
                BaseContext.setCurrentUserId(demoUserId);
                log.info("演示模式：会话 {} 自动登录用户ID={}, URI={}",
                        request.getSession().getId(), demoUserId, requestURI);
                filterChain.doFilter(request, response);
                return;
            }
            log.warn("演示模式：无法分配设备账号，放行但无用户身份，URI={}", requestURI);
            filterChain.doFilter(request, response);
            return;
        }
        // 9.走到这里就是没登录
        // 向浏览器响应一个流，让前端读到R里面的数据
        response.getWriter().write(new ObjectMapper().writeValueAsString(R.error("NOTLOGIN")));
    }

    /** 演示模式开关：true=跳过登录校验，自动以设备专属账号身份访问 */
    private static final boolean AUTO_LOGIN_ENABLED = true;
    /** 会话内保存"已分配的设备账号ID"的 key */
    private static final String DEMO_SESSION_KEY = "__demo_user_id__";
    /** 设备身份 cookie 名：不依赖 JSESSIONID，保证身份跟随浏览器稳定不变 */
    private static final String DEMO_COOKIE = "ouc_demo_uid";
    /**
     * 真实登录（邮箱+验证码）标记。
     * 真实登录的会话不做"设备身份校正"，否则会被演示用的设备账号顶掉。
     */
    public static final String REAL_LOGIN_KEY = "__real_login__";
    /** 设备账号创建锁：并发首屏请求可能同时进来，加锁避免为一台设备建出多个账号 */
    private static final Object PROVISION_LOCK = new Object();

    /** 是否为需要注入设备身份的 C 端接口路径（静态资源不注入，避免无谓建号） */
    private static boolean isClientApiPath(String uri) {
        return uri.startsWith("/shoppingCart")
                || uri.startsWith("/category")
                || uri.startsWith("/dish")
                || uri.startsWith("/setmeal")
                || uri.startsWith("/business")
                || uri.startsWith("/announcement")
                || uri.startsWith("/user/")
                || uri.startsWith("/order");
    }

    /** 解析本次请求的桌台标识：仅取请求参数（前端在桌台模式下每次都会带） */
    private String resolveTableId(HttpServletRequest request) {
        try {
            String p = request.getParameter("tableId");
            if (p != null && !p.trim().isEmpty()) {
                return p.trim();
            }
        } catch (Exception e) {
            log.debug("解析 tableId 失败(忽略): {}", e.getMessage());
        }
        return null;
    }

    /**
     * 为当前设备解析（必要时创建）一个专属的 C 端账号 ID。
     *
     * 实现要点：
     *   - 身份持久化到独立 Cookie(ouc_demo_uid)，而不是只放 Session。
     *     容器 Session 在并发/重启/无 cookie 首请求下可能重建，导致同一手机
     *     前后两次请求被识别成不同用户（购物车忽有忽无），
     *     或不同手机被识别成同一用户（购物车串菜）。
     *   - 每台设备一个独立账号：不再从固定账号池轮流分配，
     *     从根本上消除"多人共享同一账号 → 抢同一道菜"。
     */
    // 包级可见：便于单测直接验证身份解析（外部仍只经 doFilter 使用）
    Long resolveDemoUserId(HttpServletRequest request, HttpServletResponse response,
                           javax.servlet.http.HttpSession session) {
        try {
            WebApplicationContext context = getSpringContext(request);
            if (context == null) {
                return null;
            }

            // 1.配置了固定账号（reggie.demo-user-id）→ 全部会话共用该账号（单用户演示场景，默认不配置）
            String cfg = context.getEnvironment().getProperty("reggie.demo-user-id");
            if (cfg != null && !cfg.trim().isEmpty()) {
                try {
                    Long id = Long.parseLong(cfg.trim());
                    session.setAttribute(DEMO_SESSION_KEY, id);
                    return id;
                } catch (NumberFormatException ignore) {
                }
            }

            IUserService userService = context.getBean(IUserService.class);

            // 2.以浏览器 Cookie 为准：cookie 里的 uid 对应的账号仍存在且启用 → 直接使用。
            //    这是身份稳定的唯一可靠来源（容器 Session 在本项目部署环境下不持久）。
            String cookieVal = readCookie(request, DEMO_COOKIE);
            if (cookieVal != null) {
                Long cid = parseLongOrNull(cookieVal);
                // 注意：校验结果为 null 表示"暂时查不到"（DB 抖动），此时保守沿用，避免把好身份丢掉
                Boolean active = isActiveUser(userService, cid);
                if (!Boolean.FALSE.equals(active)) {
                    session.setAttribute(DEMO_SESSION_KEY, cid);
                    return cid;
                }
                // 【2026-10-18 修复】Cookie 指向的账号已不存在 / 被禁用时，必须彻底丢弃旧身份，
                // 绝不能回落到 Session 里缓存的旧 uid——否则会继续以"幽灵账号"（user 表里已无此行）
                // 写入购物车：add 返回成功、list 却查不到，表现为"加了菜购物车还是空的"，
                // 且下单时因账号不存在而失败。
                log.info("设备身份 cookie 指向的账号已失效，丢弃旧身份并重新分配：uid={}", cid);
                session.removeAttribute(DEMO_SESSION_KEY);
                session.removeAttribute("user");
            }

            // 3.同一会话内已分配过（并发首屏请求会走到这里）→ 复用前先确认账号仍然有效
            Object cached = session.getAttribute(DEMO_SESSION_KEY);
            if (cached instanceof Long) {
                Long cachedId = (Long) cached;
                Boolean active = isActiveUser(userService, cachedId);
                if (!Boolean.FALSE.equals(active)) {
                    return cachedId;
                }
                log.info("会话缓存的设备身份已失效，丢弃并重新分配：uid={}", cachedId);
                session.removeAttribute(DEMO_SESSION_KEY);
                session.removeAttribute("user");
            }

            // 4.为该设备新建独立账号，并把 Cookie 下发到浏览器
            synchronized (PROVISION_LOCK) {
                Object again = session.getAttribute(DEMO_SESSION_KEY);
                if (again instanceof Long && !Boolean.FALSE.equals(isActiveUser(userService, (Long) again))) {
                    return (Long) again;
                }
                User created = provisionDeviceUser(userService);
                if (created == null || created.getId() == null) {
                    return null;
                }
                Long uid = created.getId();
                String cookieStr = DEMO_COOKIE + "=" + uid + "; Path=/; Max-Age=" + (30 * 24 * 60 * 60)
                        + "; SameSite=Lax";
                response.addHeader("Set-Cookie", cookieStr);
                session.setAttribute(DEMO_SESSION_KEY, uid);
                log.info("演示模式：为设备新建独立账号 uid={}, email={} → Set-Cookie: {}",
                        uid, created.getEmail(), cookieStr);
                return uid;
            }
        } catch (Exception e) {
            log.error("演示模式解析设备账号失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 校验账号是否仍存在且启用。
     *
     * @return TRUE=有效；FALSE=确实无效（账号不存在/被禁用）；
     *         null=无法判定（数据库异常等），调用方应保守沿用旧身份而不是重建
     */
    private Boolean isActiveUser(IUserService userService, Long id) {
        if (id == null) {
            return Boolean.FALSE;
        }
        try {
            User u = userService.getById(id);
            return u != null && u.getStatus() != null && u.getStatus() == 1;
        } catch (Exception e) {
            log.warn("校验设备账号失败(保守沿用): id={}, err={}", id, e.getMessage());
            return null;
        }
    }

    /** 宽松解析 Long：非法值返回 null，不抛异常 */
    private static Long parseLongOrNull(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 当前会话是否处于"演示设备身份"上下文。
     * 真实登录（邮箱+验证码）的会话返回 false，避免设备账号把真实账号顶掉。
     */
    boolean isDemoIdentityContext(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getSession().getAttribute(REAL_LOGIN_KEY))) {
            return false;
        }
        return readCookie(request, DEMO_COOKIE) != null
                || request.getSession().getAttribute(DEMO_SESSION_KEY) != null;
    }

    /**
     * 新建一个设备专属的 C 端账号。
     * 邮箱统一用 dev-<随机>@device.local，与真实用户邮箱不会冲突；
     * 主键由 MyBatis-Plus 雪花算法生成（user 表 id 非自增）。
     */
    private User provisionDeviceUser(IUserService userService) {
        for (int i = 0; i < 3; i++) {
            try {
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                User u = new User();
                u.setName("顾客" + suffix.substring(8).toUpperCase());
                u.setEmail("dev-" + suffix + "@device.local");
                u.setSex("1");
                u.setStatus(1);
                userService.save(u);
                if (u.getId() != null) {
                    return u;
                }
            } catch (Exception e) {
                log.warn("设备账号创建失败(第{}次重试): {}", i + 1, e.getMessage());
            }
        }
        return null;
    }

    /** 读取指定名称的 Cookie */
    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    // 核查请求URL是否在放行URL数组中，检查本次请求是否需要放行
    private Boolean check(String[] urls, String requestURI) {
        for (String url : urls) {
            boolean match = PATH_MATCHER.match(url, requestURI);
            if (match) {
                return true;
            }
        }
        // 循环完了都匹配不上就返回false
        return false;
    }
}
