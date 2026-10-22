package edu.ouc.common;

/**
 * Author: Sihang Xie
 * Description: 基于ThreadLocal封装的工具类，用于保存和获取当前登录用户的ID
 * Date: 2022/10/2 13:56
 * Version: 0.0.1
 * Modified By:
 *
 * 2026-10-18：新增「当前桌台」ThreadLocal。
 * 购物车作用域由 userId 升级为 userId + tableId，
 * 让 C 端在同一个请求链路里（购物车增删改查、下单、加餐）都能拿到同一桌台标识，
 * 从而保证「不同账号永不共享、同账号同桌号才共享」。
 */
public class BaseContext {

    private static final ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    /** 当前请求的桌台标识（扫码点餐为桌台ID；外卖/未选桌为 null） */
    private static final ThreadLocal<String> tableLocal = new ThreadLocal<>();

    // 保存当前登录用户的ID
    public static void setCurrentUserId(Long id) {
        threadLocal.set(id);
    }

    // 获取当前登录用户的ID
    public static Long getCurrentUserId() {
        return threadLocal.get();
    }

    // 保存当前请求的桌台标识
    public static void setCurrentTableId(String tableId) {
        tableLocal.set(tableId);
    }

    // 获取当前请求的桌台标识
    public static String getCurrentTableId() {
        return tableLocal.get();
    }

    /**
     * 清理线程变量。
     * 必须由过滤器在请求结束时调用：Tomcat 线程是复用的，
     * 不清理会把上一个请求的用户/桌台带到下一个请求（身份与购物车串号）。
     */
    public static void clear() {
        threadLocal.remove();
        tableLocal.remove();
    }
}
